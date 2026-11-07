package com.easymall.task;

import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/**
 * 【仅用于测试验证，不参与正式业务运行】使用真实隔离MySQL验证可靠任务的集成测试。
 * 包含事务提交/回滚、并发去重与领取、租约恢复、重试、停机和真实子JVM崩溃场景。
 * 必须显式设置task.mysql.integration=true；普通test/package不执行本类。
 * 不触碰真实订单、支付、索引或模型；保留合成记录，不清表、不删行。
 */
@EnabledIfSystemProperty(named = "task.mysql.integration", matches = "true")
@Timeout(45)
class BusinessTaskMySqlIT {
    private TaskMySqlFixture f;
    private String type;
    /** 每个测试拥有独立类型，隔离历史合成记录；结束时只关闭连接池。 */
    @BeforeEach void open() throws Exception { f = new TaskMySqlFixture(); type = f.uniqueType(); }
    @AfterEach void close() { if (f != null) f.close(); }

    /** 另一个连接在提交前看不到两类记录，提交后业务标记与任务同时可见。 */
    @Test void commitMakesBusinessRowAndTaskVisibleTogether() {
        String key = UUID.randomUUID().toString();
        f.transaction.executeWithoutResult(status -> {
            f.jdbc.update("insert into business_task_test_marker values (?,1)", key);
            f.service.enqueue(event(type, key, 1, "{\"test\":true}", 0));
            // 另一个连接在提交前看不到业务标记和任务。
            try (var connection = f.dataSource.getConnection(); var query = connection.prepareStatement(
                    "select (select count(*) from business_task_test_marker where marker_id=?) + (select count(*) from business_task where task_type=?)")) {
                query.setString(1, key); query.setString(2, type);
                try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        });
        assertThat(f.count(type)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("select marker_value from business_task_test_marker where marker_id=?", Integer.class, key)).isEqualTo(1);
    }

    /** 回滚不会留下任务；没有事务或只有只读事务时不能投递。 */
    @Test void rollbackRemovesBothWritesAndEnqueueRequiresWritableTransaction() {
        String key = UUID.randomUUID().toString();
        f.transaction.executeWithoutResult(status -> {
            f.jdbc.update("insert into business_task_test_marker values (?,1)", key);
            f.service.enqueue(event(type, key, 1, "{}", 0));
            status.setRollbackOnly();
        });
        assertThat(f.count(type)).isZero();
        assertThat(f.jdbc.queryForObject("select count(*) from business_task_test_marker where marker_id=?", Integer.class, key)).isZero();
        assertThatThrownBy(() -> f.service.enqueue(event(type, key, 1, "{}", 0))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        f.transaction.setReadOnly(true);
        assertThatThrownBy(() -> f.transaction.execute(status -> f.service.enqueue(event(type, key, 1, "{}", 0)))).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(type)).isZero();
    }

    /** 8个线程同时提交相同事件，只能产生1条任务，字段顺序不同不算不同载荷。 */
    @Test void eightConcurrentProducersPersistOneEventAndCanonicalPayload() throws Exception {
        var ready = new CountDownLatch(8); var go = new CountDownLatch(1);
        try (var producers = Executors.newFixedThreadPool(8)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String payload = i % 2 == 0 ? "{\"b\":2,\"a\":1}" : "{\"a\":1,\"b\":2}";
                results.add(producers.submit(() -> {
                    ready.countDown(); assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    return f.transaction.execute(status -> f.service.enqueue(event(type, "same", 1, payload, 0))).getTaskId();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            Set<String> ids = new HashSet<>(); for (Future<String> result : results) ids.add(result.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1); assertThat(f.count(type)).isEqualTo(1);
        } finally { go.countDown(); }
    }

    /** 同事件不能改载荷或提前原计划；新版本可形成新任务；未到期或不支持的类型不领。 */
    @Test void changedPayloadRejectedNewVersionAllowedAndDuplicateDoesNotReschedule() {
        BusinessTask first = f.transaction.execute(status -> f.service.enqueue(event(type, "same", 1, "{\"x\":1}", 30)));
        BusinessTask duplicate = f.transaction.execute(status -> f.service.enqueue(event(type, "same", 1, "{\"x\":1}", 0)));
        assertThat(duplicate.getTaskId()).isEqualTo(first.getTaskId());
        assertThat(duplicate.getNextRunAt()).isEqualTo(first.getNextRunAt());
        assertThatThrownBy(() -> f.transaction.execute(status -> f.service.enqueue(event(type, "same", 1, "{\"x\":2}", 0)))).isInstanceOf(BusinessException.class);
        f.transaction.executeWithoutResult(status -> f.service.enqueue(event(type, "same", 2, "{\"x\":2}", 0)));
        assertThat(f.count(type)).isEqualTo(2);
        assertThat(f.claim(type, 8)).singleElement().satisfies(task -> assertThat(task.getBizVersion()).isEqualTo(2));
        assertThat(f.claim(f.uniqueType(), 8)).isEmpty();
    }

    /** 两个领取者并发访问真实行锁，领取结果不能包含同一任务的重复执行资格。 */
    @Test void concurrentWorkersClaimDisjointBatches() throws Exception {
        for (int i = 0; i < 20; i++) f.submit(type, "row-" + i);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<List<BusinessTask>> claim = () -> { go.await(); return f.claim(type, 10); };
            Future<List<BusinessTask>> a = pool.submit(claim), b = pool.submit(claim); go.countDown();
            List<BusinessTask> rows = new ArrayList<>(a.get(10, TimeUnit.SECONDS)); rows.addAll(b.get(10, TimeUnit.SECONDS));
            // SKIP LOCKED允许少领，补领剩余到期任务，任何时刻都不能重复领取已持有租约的行。
            rows.addAll(f.claim(type, 20));
            assertThat(rows).hasSize(20);
            assertThat(rows.stream().map(BusinessTask::getTaskId)).doesNotHaveDuplicates();
            assertThat(rows).allSatisfy(task -> { assertThat(task.getAttempts()).isEqualTo(1); assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING); });
        } finally { go.countDown(); }
    }

    /** 等待真实租约自然到期再接管，旧owner不能续租、成功或登记失败。 */
    @Test void expiredLeaseIsReclaimedAndOldOwnerCannotRenewOrWrite() throws Exception {
        BusinessTask submitted = f.submit(type, "lease");
        BusinessTask old = f.claim(type, 1).getFirst();
        await(() -> !f.service.ownsLease(old), 6000);
        assertThat(f.service.renew(old, 3)).isFalse();
        BusinessTask replacement = f.claim(type, 1).getFirst();
        assertThat(replacement.getAttempts()).isEqualTo(2);
        assertThat(replacement.getLeaseOwner()).isNotEqualTo(old.getLeaseOwner());
        assertThat(f.service.succeed(old)).isFalse(); assertThat(f.service.fail(old, "HANDLER_FAILED")).isFalse();
        assertThat(f.service.succeed(replacement)).isTrue();
        assertThat(f.service.findById(submitted.getTaskId()).getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    /** 验证退避封顶、最多3次进入DEAD，以及只允许DEAD人工重试。 */
    @Test void retriesBackOffAndStopAtDeadThenManualRetryIsGuarded() throws Exception {
        f.properties.setInitialBackoffMillis(1000); f.properties.setMaxBackoffMillis(1500);
        BusinessTask submitted = f.submit(type, "retry");
        for (int attempt = 1; attempt <= 3; attempt++) {
            BusinessTask current = f.claim(type, 1).getFirst();
            assertThat(current.getAttempts()).isEqualTo(attempt);
            assertThat(f.service.fail(current, "HANDLER_FAILED")).isTrue();
            BusinessTask failed = f.service.findById(submitted.getTaskId());
            assertThat(failed.getStatus()).isEqualTo(attempt == 3 ? TaskStatus.DEAD : TaskStatus.RETRY);
            assertThat(Duration.between(failed.getUpdatedAt(), failed.getNextRunAt()).toMillis()).isEqualTo(attempt == 1 ? 1000 : 1500);
            assertThat(f.claim(type, 1)).isEmpty();
            // 仅提前本测试合成任务的下次时间，避免为退避断言反复等待；不修改租约伪造恢复。
            if (attempt < 3) f.jdbc.update("update business_task set next_run_at=UTC_TIMESTAMP(6) where task_id=?", submitted.getTaskId());
        }
        assertThat(f.service.listDead(100)).extracting(BusinessTask::getTaskId).contains(submitted.getTaskId());
        assertThat(f.service.retryDead(submitted.getTaskId())).isTrue();
        assertThat(f.service.retryDead(submitted.getTaskId())).isFalse();
        BusinessTask retried = f.claim(type, 1).getFirst(); assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(f.service.succeed(retried)).isTrue();
        assertThat(f.service.retryDead(submitted.getTaskId())).isFalse();
    }

    /** 最后一次执行没有写回结果时，到期扫描仍应把它转DEAD，而非永远RUNNING。 */
    @Test void exhaustedCrashBecomesDeadInsteadOfRunningForever() throws Exception {
        f.properties.setMaxAttempts(1); BusinessTask task = f.submit(type, "last-attempt");
        BusinessTask claimed = f.claim(type, 1).getFirst();
        await(() -> !f.service.ownsLease(claimed), 6000);
        assertThat(f.claim(type, 1)).isEmpty();
        BusinessTask dead = f.service.findById(task.getTaskId());
        assertThat(dead.getStatus()).isEqualTo(TaskStatus.DEAD); assertThat(dead.getLastError()).isEqualTo("LEASE_EXHAUSTED");
        assertThat(f.service.retryDead(task.getTaskId())).isTrue();
    }

    /** 处理器耗时超过原租约，持续续租后不被重领；处理器执行时没有领取事务。 */
    @Test void longHandlerRenewsLeaseOutsideTransaction() throws Exception {
        BusinessTask task = f.submit(type, "slow");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var insideTransaction = new AtomicBoolean(true);
        try (var worker = worker(execution -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown(); assertThat(release.await(12, TimeUnit.SECONDS)).isTrue(); execution.requireActiveLease();
        })) {
            worker.start(); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            BusinessTask started = f.service.findById(task.getTaskId());
            await(() -> f.service.findById(task.getTaskId()).getLeaseUntil().isAfter(started.getLeaseUntil().plusSeconds(3)), 7000);
            assertThat(f.claim(type, 1)).isEmpty(); assertThat(insideTransaction).isFalse();
            release.countDown(); await(() -> state(task) == TaskStatus.SUCCESS, 5000);
        } finally { release.countDown(); }
    }

    /** 8个任务最大并发仍为2；测试处理器故意前两次失败，验证恢复及异常内容不入库。 */
    @Test void concurrencyIsBoundedAndHandlerFailuresRetryWithoutStoringExceptionText() throws Exception {
        List<BusinessTask> tasks = new ArrayList<>(); for (int i = 0; i < 8; i++) tasks.add(f.submit(type, "row-" + i));
        var bothEntered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        try (var worker = worker(task -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try {
                bothEntered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                if (task.attempt() < 3) throw new IllegalStateException("SECRET_RAW_PAYLOAD_MUST_NOT_BE_STORED");
            } finally { active.decrementAndGet(); }
        })) {
            worker.start(); assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f.jdbc.queryForObject("select count(*) from business_task where task_type=? and status='RUNNING'", Integer.class, type)).isEqualTo(2);
            assertThat(worker.inFlightCount()).isEqualTo(2); release.countDown();
            await(() -> tasks.stream().allMatch(task -> state(task) == TaskStatus.SUCCESS), 12000);
            assertThat(maximum.get()).isEqualTo(2);
            assertThat(tasks).allSatisfy(task -> {
                BusinessTask saved = f.service.findById(task.getTaskId()); assertThat(saved.getAttempts()).isEqualTo(3); assertThat(saved.getLastError()).isNull();
            });
        } finally { release.countDown(); }
    }

    /** 停机停止新领取，在宽限内完成在途任务，其余任务仍保留READY。 */
    @Test void gracefulStopWaitsForCurrentWorkAndLeavesNewWorkReady() throws Exception {
        f.properties.getWorker().setThreads(1);
        BusinessTask first = f.submit(type, "first"), second = f.submit(type, "second");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var worker = worker(task -> { entered.countDown(); release.await(); })) {
            worker.start(); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var stopper = Executors.newSingleThreadExecutor()) {
                Future<?> stopped = stopper.submit(() -> { worker.stop(); }); await(() -> !worker.isRunning(), 2000);
                release.countDown(); stopped.get(5, TimeUnit.SECONDS);
            }
            assertThat(state(first)).isEqualTo(TaskStatus.SUCCESS); assertThat(state(second)).isEqualTo(TaskStatus.READY);
        } finally { release.countDown(); }
    }

    /** 故意让测试处理器忽略中断，验证停机后迟到结果不能提交，任务仍能被接管。 */
    @Test void forcedStopCannotAcknowledgeLateHandlerAndLeaseCanRecover() throws Exception {
        f.properties.getWorker().setShutdownGraceMillis(50);
        BusinessTask task = f.submit(type, "interrupted"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        try (var worker = worker(execution -> {
            entered.countDown();
            try { while (!release.await(50, TimeUnit.MILLISECONDS)) { } }
            catch (InterruptedException ignored) { while (release.getCount() > 0) Thread.onSpinWait(); }
            finally { finished.countDown(); }
        })) {
            worker.start(); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); worker.stop();
            assertThat(state(task)).isEqualTo(TaskStatus.RUNNING);
            release.countDown(); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            await(() -> worker.inFlightCount() == 0, 2000);
            assertThat(state(task)).isEqualTo(TaskStatus.RUNNING);
            BusinessTask old = f.service.findById(task.getTaskId()); await(() -> !f.service.ownsLease(old), 6000);
            assertThat(f.service.succeed(f.claim(type, 1).getFirst())).isTrue();
        } finally { release.countDown(); }
    }

    /** 启动TaskCrashProbe真实硬退出；父进程使用另一个Worker恢复并完成第2次执行。 */
    @Test void hardCrashedJvmTaskRecoversInAnotherWorker() throws Exception {
        BusinessTask task = f.submit(type, "crash");
        Process child = child("com.easymall.task.TaskCrashProbe", false, type);
        try {
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).isEqualTo(23);
            BusinessTask crashed = f.service.findById(task.getTaskId());
            assertThat(crashed.getStatus()).isEqualTo(TaskStatus.RUNNING); assertThat(crashed.getAttempts()).isEqualTo(1);
            try (var recovered = worker(TaskExecution::requireActiveLease)) {
                recovered.start(); await(() -> state(task) == TaskStatus.SUCCESS, 8000);
            }
            assertThat(f.service.findById(task.getTaskId()).getAttempts()).isEqualTo(2);
        } finally { stopChild(child); }
    }

    /** 专用启动入口在真实子JVM中消费无副作用Smoke任务，空闲时进程也不退出。 */
    @Test void standaloneJvmConsumesOnlySyntheticSmokeAndKeepsRunningWhenIdle() throws Exception {
        BusinessTask task = f.submit("SMOKE_NOOP", UUID.randomUUID().toString());
        Path backend = f.envFile.getParent().getParent().getParent().resolve("easymall-java");
        Process child = child("com.easymall.task.BusinessTaskWorkerApplication", true,
                "--spring.config.location=" + backend.resolve("config/local/worker.example.yml").toUri(),
                "--business-task.smoke.enabled=true", "--business-task.worker.poll-interval-millis=100");
        try { await(() -> state(task) == TaskStatus.SUCCESS || !child.isAlive(), 15000); assertThat(state(task)).isEqualTo(TaskStatus.SUCCESS); assertThat(child.isAlive()).isTrue(); }
        finally { stopChild(child); }
    }

    /** 测试临时处理器，只注册本测试独有类型，不代表真实订单或同步业务已接入。 */
    private BusinessTaskWorker worker(Work action) {
        return new BusinessTaskWorker(f.service, f.properties, List.of(new BusinessTaskHandler() {
            public String taskType() { return type; }
            public void handle(TaskExecution task) throws Exception { action.run(task); }
        }));
    }
    private TaskStatus state(BusinessTask task) { return f.service.findById(task.getTaskId()).getStatus(); }
    private static TaskSubmission event(String type, String key, long version, String payload, long delay) { return new TaskSubmission(type, key, version, payload, Duration.ofSeconds(delay)); }
    private static void await(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition did not become true within " + timeoutMillis + " ms");
            Thread.sleep(25);
        }
    }
    /** 子JVM使用参数文件规避Windows命令长度限制，密码只放进环境变量而不写参数文件。 */
    private Process child(String main, boolean smoke, String... args) throws Exception {
        Path directory = Path.of("target/step11a").toAbsolutePath(); Files.createDirectories(directory);
        String id = UUID.randomUUID().toString(); Path argfile = directory.resolve(id + ".args");
        List<String> lines = new ArrayList<>(List.of("-cp", quoted(System.getProperty("java.class.path")),
                "-Dtask.mysql.integration=true", quoted("-Dtask.mysql.env-file=" + f.envFile), main));
        for (String arg : args) lines.add(quoted(arg));
        Files.write(argfile, lines, StandardCharsets.UTF_8);
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "@" + argfile);
        process.redirectErrorStream(true).redirectOutput(directory.resolve(id + ".log").toFile());
        if (smoke) { process.environment().put("TASK_DB_PASSWORD", f.password); process.environment().put("TASK_DB_URL", TaskMySqlFixture.URL); process.environment().put("TASK_DB_USER", "baseline"); }
        return process.start();
    }
    private static String quoted(String argument) { return "\"" + argument.replace('\\', '/').replace("\"", "\\\"") + "\""; }
    /** 仅结束本测试持有句柄的子进程，不按进程名批量停止其他Java程序。 */
    private static void stopChild(Process process) throws Exception {
        if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); } }
    }
    /** 【仅用于测试】给临时处理器提供可抛异常的测试动作。 */
    @FunctionalInterface private interface Work { void run(TaskExecution task) throws Exception; }
}
