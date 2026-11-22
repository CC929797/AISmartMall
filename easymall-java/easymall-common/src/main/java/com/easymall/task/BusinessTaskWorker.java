package com.easymall.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【后续业务基础设施】独立后台执行器，负责“领取任务 → 调用处理器 → 记录结果”。
 * <p>独立 Worker 入口和 Web 支付任务装配均可使用。领取事务提交后再执行处理器，
 * 执行线程不与AI或HTTP共用；本类只调度，具体订单或商品操作由BusinessTaskHandler提供。
 * 框架允许故障后再次执行，不能据此假定业务副作用只发生一次。
 */
public final class BusinessTaskWorker implements SmartLifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BusinessTaskWorker.class);
    private final BusinessTaskService service;
    private final BusinessTaskProperties.Worker options;
    /** 按任务类型查找处理器；没有注册的类型不会被本Worker领取。 */
    private final Map<String, BusinessTaskHandler> handlers;
    private final AtomicBoolean running = new AtomicBoolean();
    /** 本进程已交给执行池、但尚未结束的任务，用于停机时撤销本地执行资格。 */
    private final Set<Run> inFlight = ConcurrentHashMap.newKeySet();
    /** 只负责轮询与短事务领取，不运行具体业务。 */
    private ScheduledExecutorService poller;
    /** 独立续租线程，避免长业务占满执行线程后无人续租。 */
    private ScheduledExecutorService heartbeats;
    /** 执行具体处理器的有界线程池。 */
    private ThreadPoolExecutor executor;
    /** 先预留执行容量，再领取任务，避免大量任务领出后排队等待至租约过期。 */
    private Semaphore capacity;

    public BusinessTaskWorker(BusinessTaskService service, BusinessTaskProperties properties, List<BusinessTaskHandler> handlers) {
        properties.validate();
        this.service = service; this.options = properties.getWorker();
        Map<String, BusinessTaskHandler> registry = new HashMap<>();
        for (BusinessTaskHandler handler : handlers) {
            BusinessTaskService.validateType(handler.taskType());
            if (registry.putIfAbsent(handler.taskType(), handler) != null) throw new IllegalArgumentException("Duplicate task handler type");
        }
        if (registry.isEmpty() || registry.size() > 64) throw new IllegalArgumentException("Register 1 to 64 task handlers before starting Worker");
        this.handlers = Map.copyOf(registry);
    }

    /** 启动独立线程；若上次停机仍有线程未退出，拒绝重启以免混用两轮执行状态。 */
    @Override public synchronized void start() {
        if (executor != null && (!executor.isTerminated() || !poller.isTerminated() || !heartbeats.isTerminated())) {
            if (running.get()) return;
            throw new IllegalStateException("Previous Worker execution has not terminated");
        }
        if (!running.compareAndSet(false, true)) return;
        capacity = new Semaphore(options.getThreads());
        executor = new ThreadPoolExecutor(options.getThreads(), options.getThreads(), 0L, TimeUnit.MILLISECONDS,
                // 许可已将运行中+已领取任务限制为threads；小队列只吸收线程释放许可到返回run之间的交接窗口。
                new ArrayBlockingQueue<>(options.getThreads()), threads("business-task-execute-"), new ThreadPoolExecutor.AbortPolicy());
        heartbeats = Executors.newSingleThreadScheduledExecutor(threads("business-task-lease-"));
        poller = Executors.newSingleThreadScheduledExecutor(threads("business-task-poll-"));
        poller.scheduleWithFixedDelay(this::poll, 0, options.getPollIntervalMillis(), TimeUnit.MILLISECONDS);
        log.info("Business task Worker started; handlers={}, concurrency={}", handlers.size(), options.getThreads());
    }

    /** 每轮最多领取“批次上限”和“空闲许可数”中的较小值，领取提交后再分派。 */
    private void poll() {
        int reserved = 0;
        try {
            if (!running.get()) return;
            while (reserved < options.getBatchSize() && capacity.tryAcquire()) reserved++;
            if (reserved == 0) return;
            List<BusinessTask> claimed = service.claimBatch(handlers.keySet(), reserved, options.getLeaseSeconds());
            for (BusinessTask task : claimed) {
                Run run = new Run(task);
                if (!running.get()) break;
                inFlight.add(run);
                try {
                    executor.execute(() -> execute(run));
                    reserved--;
                } catch (RejectedExecutionException rejected) {
                    inFlight.remove(run);
                    // 未执行任务保留RUNNING记录，租约到期后恢复；不删除任务。
                }
            }
        } catch (Exception failure) {
            log.warn("Task poll failed; code=POLL_FAILED, exceptionType={}", failure.getClass().getSimpleName());
        } finally {
            // 没领到或没成功分派的许可立即归还；已分派任务的许可由execute归还。
            if (reserved > 0) capacity.release(reserved);
        }
    }

    /**
     * 在领取事务之外执行处理器，同时周期续租。
     * 续租失败就标记执行资格丢失；即使处理器随后返回，也不再以旧身份提交结果。
     */
    private void execute(Run run) {
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                if (run.lost.get()) return;
                try { if (!service.renew(run.task, options.getLeaseSeconds())) run.lost.set(true); }
                // 无法确认租约时先停止本地结果写入，等待数据库租约恢复机制处理。
                catch (Exception failure) { run.lost.set(true); }
            }, options.getHeartbeatMillis(), options.getHeartbeatMillis(), TimeUnit.MILLISECONDS);
            TaskExecution task = new TaskExecution(run.task.getTaskId(), run.task.getTaskType(), run.task.getBizKey(),
                    run.task.getBizVersion(), run.task.getPayload(), run.task.getAttempts(),
                    () -> !run.lost.get() && service.ownsLease(run.task));
            task.requireActiveLease();
            // 此时没有持有领取事务的行锁；处理器自行负责业务事务、幂等和外部版本保护。
            handlers.get(task.taskType()).handle(task);
            if (!run.lost.get() && service.succeed(run.task)) {
                log.info("Task completed; taskId={}, type={}, attempt={}", task.taskId(), task.taskType(), task.attempt());
            }
        } catch (TaskDeferredException pending) {
            try {
                if (!run.lost.get()) {
                    service.defer(run.task);
                }
            } catch (RuntimeException storageFailure) {
                // 暂时无法写入延后结果时，原租约到期后仍可恢复任务。
                log.warn("Task deferral failed; taskId={}", run.task.getTaskId());
            }
        } catch (Exception failure) {
            try {
                if (!run.lost.get()) service.fail(run.task, "HANDLER_FAILED");
            } catch (Exception storageFailure) {
                log.warn("Task result persistence failed; taskId={}, code=RESULT_WRITE_FAILED", run.task.getTaskId());
            }
            log.warn("Task failed; taskId={}, type={}, attempt={}, code=HANDLER_FAILED, exceptionType={}",
                    run.task.getTaskId(), run.task.getTaskType(), run.task.getAttempts(), failure.getClass().getSimpleName());
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            inFlight.remove(run);
            capacity.release();
        }
    }

    /** 停止新领取，等待宽限期；超时任务保留数据库记录，由租约到期恢复。 */
    @Override public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        poller.shutdownNow();
        executor.shutdown();
        try { executor.awaitTermination(options.getShutdownGraceMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        // 超过停机宽限的在途任务不能再写结果。外部在途副作用仍需处理器自己的幂等/版本保护。
        inFlight.forEach(run -> run.lost.set(true));
        executor.shutdownNow();
        heartbeats.shutdownNow();
        log.info("Business task Worker stopped; remaining={}", inFlight.size());
    }

    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running.get(); }
    /** Spring停机时较早停止Worker，让其在数据库等资源关闭前完成收尾。 */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public void close() { stop(); }
    /** 当前进程在途任务数，可用于观察运行状态，也用于并发边界测试。 */
    public int inFlightCount() { return inFlight.size(); }

    /** 使用具名的非守护线程，保证无HTTP的独立Worker在空闲时也继续存活。 */
    private static ThreadFactory threads(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return action -> new Thread(action, prefix + count.incrementAndGet());
    }
    /** 【运行时内部状态】一次领取对应的快照和本地失效标记，不是数据库实体或测试类。 */
    private static final class Run {
        final BusinessTask task;
        final AtomicBoolean lost = new AtomicBoolean();
        Run(BusinessTask task) { this.task = task; }
    }
}
