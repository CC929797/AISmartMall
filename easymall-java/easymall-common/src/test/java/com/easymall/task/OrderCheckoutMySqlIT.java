package com.easymall.task;

import com.easymall.entity.dto.*;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderItem;
import com.easymall.exception.BusinessException;
import com.easymall.order.CheckoutInput;
import com.easymall.order.OrderRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 【仅用于测试验证，不参与正式业务运行】固定13306/easymall_test验证真实订单事务、行锁与条件库存SQL。
 * 支付和Redis为替身；合成数据保留；包含提交前/提交后两个真实子JVM硬崩溃场景。
 */
@EnabledIfSystemProperty(named = "order.mysql.integration", matches = "true")
@Timeout(45)
class OrderCheckoutMySqlIT {
    private CheckoutMySqlFixture f;
    @BeforeEach void open() throws Exception { f = new CheckoutMySqlFixture(); }
    @AfterEach void close() { if (f != null) f.close(); }

    @Test void verifiedPaymentConfirmsOrderOnceAndKeepsInventoryReservation() {
        PayInfoDTO created = f.service.postOrder(f.user, f.order(1));
        PayOrderNotifyDTO confirmed = new PayOrderNotifyDTO(created.getPayOrderId(), "SYNTHETIC_CHANNEL_PAYMENT");
        confirmed.setAmount(created.getAmount());
        f.service.payOrderSuccess(confirmed); f.service.payOrderSuccess(confirmed);
        assertThat(f.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, created.getOrderId())).isEqualTo(1);
        assertThat(f.stock()).isEqualTo(4);
        verify(f.redis, times(1)).addOrder2DelayQueue(eq(com.easymall.entity.constants.Constants.REDIS_KEY_ORDER_DELAY_QUEUE_DELIVERY), eq(1), eq(created.getPayOrderId()));
    }

    @Test void mismatchedProviderAmountCannotConfirmPayment() {
        PayInfoDTO created = f.service.postOrder(f.user, f.order(1));
        PayOrderNotifyDTO confirmed = new PayOrderNotifyDTO(created.getPayOrderId(), "SYNTHETIC_CHANNEL_PAYMENT");
        confirmed.setAmount(new BigDecimal("0.01"));
        assertThatThrownBy(() -> f.service.payOrderSuccess(confirmed)).isInstanceOf(BusinessException.class).hasMessageContaining("金额");
        assertThat(f.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, created.getOrderId())).isZero();
        assertThat(f.stock()).isEqualTo(4);
    }

    @Test void firstSubmissionThroughNotSupportedBoundaryCreatesOrderAndPayment() {
        // 复现正式服务的@Transactional(NOT_SUPPORTED)：仍有Spring同步上下文，MyBatis会复用会话缓存。
        var boundary = new TransactionTemplate(new DataSourceTransactionManager(f.db.dataSource));
        boundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        PostOrderDTO request = f.order(1);
        PayInfoDTO result = boundary.execute(status -> f.service.postOrder(f.user, request));
        assertThat(result.getRequestStatus()).isEqualTo("CREATED");
        assertThat(result.getPaymentStatus()).isEqualTo("READY");
        assertThat(result.getPayInfo()).contains("synthetic-no-payment");
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        assertThat(f.eventCount("ORDER_CREATED", result.getPayOrderId())).isEqualTo(1);
        verify(f.payment, times(1)).getPayUrl(any(), eq(result.getPayOrderId()), anyString(), any());
    }

    @Test void requestQueryInSameSynchronizationSeesIndependentCommit() {
        var boundary = new TransactionTemplate(new DataSourceTransactionManager(f.db.dataSource));
        boundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        PostOrderDTO request = f.order(1);
        boundary.executeWithoutResult(status -> {
            assertThat(f.requestService.find(f.user, request.getRequestId())).isNull();
            PayInfoDTO created = f.service.postOrder(f.user, request);
            assertThat(f.service.getOrderRequest(f.user, request.getRequestId()).getRequestStatus()).isEqualTo("CREATED");
            assertThat(f.service.postOrder(f.user, request).getPayOrderId()).isEqualTo(created.getPayOrderId());
        });
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
    }

    @Test void abandonedReservationRecoversThroughNotSupportedBoundaryWithOriginalPaymentId() {
        PostOrderDTO request = f.order(1);
        OrderRequest abandoned = new OrderRequest();
        abandoned.setUserId(f.user); abandoned.setRequestId(request.getRequestId());
        abandoned.setRequestHash(CheckoutInput.normalize(request).digest());
        abandoned.setPayOrderId(com.easymall.utils.StringTools.createPayOrderId());
        abandoned.setLeaseOwner(UUID.randomUUID().toString().replace("-", ""));
        f.db.transaction.executeWithoutResult(status -> f.requests.insert(abandoned, 3));
        // 仅提前本测试合成记录的到期时间，模拟修复前遗留的PROCESSING，不更新日常订单。
        f.db.jdbc.update("update order_request set lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) where user_id=? and request_id=?", f.user, request.getRequestId());
        var boundary = new TransactionTemplate(new DataSourceTransactionManager(f.db.dataSource));
        boundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        PayInfoDTO result = boundary.execute(status -> {
            assertThat(f.service.getOrderRequest(f.user, request.getRequestId()).getRequestStatus()).isEqualTo("RETRY");
            return f.service.postOrder(f.user, request);
        });
        assertThat(result.getRequestStatus()).isEqualTo("CREATED");
        assertThat(result.getPaymentStatus()).isEqualTo("READY");
        assertThat(result.getPayOrderId()).isEqualTo(abandoned.getPayOrderId());
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        assertThat(f.eventCount("ORDER_CREATED", result.getPayOrderId())).isEqualTo(1);
    }

    @Test void realInventoryLockTimeoutReleasesAttemptAndOriginalRequestCanRetry() throws Exception {
        PostOrderDTO request = f.order(1);
        String payId;
        // 仅锁定本测试的合成SKU，迫使真实库存SQL超过业务事务的2秒超时。
        try (var blocking = f.db.dataSource.getConnection()) {
            blocking.setAutoCommit(false);
            try {
                try (var lock = blocking.prepareStatement("select stock from product_sku where product_id=? for update")) {
                    lock.setString(1, f.product); try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                PayInfoDTO busy = f.service.postOrder(f.user, request);
                assertThat(busy.getRequestStatus()).isEqualTo("RETRY");
                payId = busy.getPayOrderId();
                assertThat(f.orderCount()).isZero(); assertThat(f.stock()).isEqualTo(5);
                assertThat(f.requestService.find(f.user, request.getRequestId()).getLeaseOwner()).isNull();
                assertThat(f.eventCount("ORDER_CREATED", payId)).isZero();
                verifyNoInteractions(f.payment);
            } finally { blocking.rollback(); }
        }
        PayInfoDTO retried = f.service.postOrder(f.user, request);
        assertThat(retried.getRequestStatus()).isEqualTo("CREATED");
        assertThat(retried.getPaymentStatus()).isEqualTo("READY");
        assertThat(retried.getPayOrderId()).isEqualTo(payId);
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        assertThat(f.eventCount("ORDER_CREATED", payId)).isEqualTo(1);
    }

    @Test void duplicateSkuCreatesOnePositiveDetailAndCorrectAmount() {
        PostOrderDTO request = f.order(2);
        request.setOrderList(List.of(CheckoutMySqlFixture.item(f.product, f.property, 2), CheckoutMySqlFixture.item(f.product, f.property, 3)));
        PayInfoDTO result = f.service.postOrder(f.user, request);
        assertThat(result.getRequestStatus()).isEqualTo("CREATED"); assertThat(result.getAmount()).isEqualByComparingTo("61.70");
        assertThat(f.stock()).isZero(); assertThat(f.orderCount()).isEqualTo(1);
        assertThat(f.db.jdbc.queryForObject("select buy_count from order_item where order_id=?", Integer.class, result.getOrderId())).isEqualTo(5);
        assertThat(request.getOrderList()).extracting(PostOrderItemDTO::getBuyCount).containsExactly(2, 3);
        request.setOrderList(List.of(CheckoutMySqlFixture.item(f.product, f.property, 5)));
        assertThat(f.service.postOrder(f.user, request).getPayOrderId()).isEqualTo(result.getPayOrderId());
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.eventCount("ORDER_CREATED", result.getPayOrderId())).isEqualTo(1);
    }

    @Test void stockFiveUnderEightConcurrentBuyersNeverOversells() throws Exception {
        List<PayInfoDTO> successes = parallel(8, index -> {
            try { return f.service.postOrder(f.user, f.order(1)); }
            catch (BusinessException insufficient) { return null; }
        }).stream().filter(Objects::nonNull).toList();
        assertThat(successes).hasSize(5); assertThat(f.stock()).isZero(); assertThat(f.orderCount()).isEqualTo(5);
        assertThat(f.db.jdbc.queryForObject("select sum(i.buy_count) from order_item i join order_info o on o.order_id=i.order_id where o.user_id=?", Integer.class, f.user)).isEqualTo(5);
        assertThat(successes).allSatisfy(result -> assertThat(result.getRequestStatus()).isEqualTo("CREATED"));
    }

    @Test void concurrentSameRequestHasOnePaymentGroupAndOneDeduction() throws Exception {
        PostOrderDTO request = f.order(1);
        List<PayInfoDTO> responses = parallel(8, index -> f.service.postOrder(f.user, request));
        assertThat(responses.stream().map(PayInfoDTO::getPayOrderId).distinct()).hasSize(1);
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        assertThat(f.requests.find(f.user, request.getRequestId()).getStatus()).isEqualTo("CREATED");
        assertThat(f.eventCount("ORDER_CREATED", responses.getFirst().getPayOrderId())).isEqualTo(1);
    }

    @Test void sameRequestWithDifferentContentRejectedAndForeignQueryDoesNotLeak() {
        PostOrderDTO request = f.order(1); PayInfoDTO result = f.service.postOrder(f.user, request);
        request.getOrderList().getFirst().setBuyCount(2);
        assertThatThrownBy(() -> f.service.postOrder(f.user, request)).isInstanceOf(BusinessException.class).hasMessageContaining("不同");
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        assertThat(f.service.getOrderRequest(f.user, request.getRequestId()).getPayOrderId()).isEqualTo(result.getPayOrderId());
        assertThatThrownBy(() -> f.service.getOrderRequest("OTHER-USER", request.getRequestId())).isInstanceOf(BusinessException.class);
    }

    @Test void eventWriteFailureRollsBackOrdersDetailsLogisticsAndStock() {
        BusinessTaskService failing = spy(f.db.service);
        doThrow(new IllegalStateException("synthetic-event-write-failure")).when(failing).enqueue(any());
        ReflectionTestUtils.setField(f.service, "businessTaskService", failing);
        PostOrderDTO request = f.order(2);
        assertThatThrownBy(() -> f.service.postOrder(f.user, request)).isInstanceOf(IllegalStateException.class);
        assertThat(f.orderCount()).isZero(); assertThat(f.stock()).isEqualTo(5);
        assertThat(f.db.jdbc.queryForObject("select count(*) from order_logistics_info where user_id=?", Integer.class, f.user)).isZero();
        assertThat(f.db.jdbc.queryForObject("select count(*) from order_item where product_id=?", Integer.class, f.product)).isZero();
        var reserved = f.requests.find(f.user, request.getRequestId()); assertThat(reserved.getStatus()).isEqualTo("RETRY");
        assertThat(f.eventCount("ORDER_CREATED", reserved.getPayOrderId())).isZero();
        ReflectionTestUtils.setField(f.service, "businessTaskService", f.db.service);
        assertThat(f.service.postOrder(f.user, request).getPayOrderId()).isEqualTo(reserved.getPayOrderId());
        assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(3);
    }

    @Test void concurrentCancellationRestoresStockExactlyOnce() throws Exception {
        PayInfoDTO created = f.service.postOrder(f.user, f.order(3));
        parallel(8, index -> { f.service.cancelOrder(f.user, created.getOrderId(), OrderStatusEnum.CANCELLED); return true; });
        assertThat(f.stock()).isEqualTo(5);
        assertThat(f.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, created.getOrderId())).isEqualTo(4);
        assertThat(f.eventCount("ORDER_CANCELLED", created.getPayOrderId())).isEqualTo(1);
        verify(f.payment, times(1)).closeOrder(created.getPayOrderId());
    }

    @Test void databaseBatchAggregatesRepeatedSkuBeforeConditionalUpdate() {
        OrderItem a = stockChange(-2), b = stockChange(-3);
        assertThat(f.skus.updateStockBatch(List.of(a, b))).isEqualTo(1); assertThat(f.stock()).isZero();
        assertThat(f.skus.updateStockBatch(List.of(stockChange(-1)))).isZero(); assertThat(f.stock()).isZero();
        assertThat(f.skus.updateStockBatch(List.of(stockChange(2), stockChange(3)))).isEqualTo(1); assertThat(f.stock()).isEqualTo(5);
    }

    @Test void paymentFailureKeepsCreatedOrdersAndRetryUsesSameIdOutsideTransaction() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); throw new IllegalStateException("synthetic-channel-offline");
        }).when(f.payment).getPayUrl(any(), anyString(), anyString(), any());
        PostOrderDTO request = f.order(1); PayInfoDTO unavailable = f.service.postOrder(f.user, request);
        assertThat(unavailable.getRequestStatus()).isEqualTo("CREATED"); assertThat(unavailable.getPaymentStatus()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.getPayInfo()).isNull(); assertThat(f.stock()).isEqualTo(4); assertThat(f.orderCount()).isEqualTo(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PayInfoDTO("synthetic-recovered", call.getArgument(1), call.getArgument(3));
        }).when(f.payment).getPayUrl(any(), anyString(), anyString(), any());
        PayInfoDTO retry = f.service.postOrder(f.user, request);
        assertThat(retry.getPayOrderId()).isEqualTo(unavailable.getPayOrderId()); assertThat(retry.getPaymentStatus()).isEqualTo("READY");
        assertThat(f.stock()).isEqualTo(4); assertThat(f.orderCount()).isEqualTo(1);
    }

    @Test void retryPaymentUsesWholeGroupAmountWithoutReplacingAnyPaymentId() {
        String otherProduct = "Q8" + f.suffix, otherProperty = "W8" + f.suffix; f.addProduct(otherProduct, otherProperty, 5);
        PostOrderDTO request = f.order(1); request.setOrderFrom(1);
        request.setOrderList(List.of(CheckoutMySqlFixture.item(f.product, f.property, 1), CheckoutMySqlFixture.item(otherProduct, otherProperty, 2)));
        PayInfoDTO original = f.service.postOrder(f.user, request); assertThat(f.orderCount()).isEqualTo(2);
        PayInfoDTO retry = f.service.getPayInfo(f.user, original.getOrderId());
        assertThat(retry.getAmount()).isEqualByComparingTo("37.02"); assertThat(retry.getPayOrderId()).isEqualTo(original.getPayOrderId());
        assertThat(f.db.jdbc.queryForObject("select count(distinct pay_order_id) from order_info where user_id=?", Integer.class, f.user)).isEqualTo(1);
        verify(f.payment, never()).closeOrder(anyString());
    }

    @Test void cartCheckoutRemovesOnlySelectedRowsForCurrentUser() {
        String otherProduct = "Q8" + f.suffix, otherProperty = "W8" + f.suffix; f.addProduct(otherProduct, otherProperty, 5);
        String[] owners = { f.user, f.user, "OTHER-USER" };
        String[] products = { f.product, otherProduct, f.product };
        String[] properties = { f.property, otherProperty, f.property };
        for (int i = 0; i < 3; i++) f.db.jdbc.update(
                "insert into product_cart(cart_id,user_id,product_id,property_value_ids,property_value_id_hash,buy_count) values (?,?,?,?,?,1)",
                "C" + i + f.suffix, owners[i], products[i], properties[i], com.easymall.utils.StringTools.encodeByMD5(properties[i]));
        PostOrderDTO request = f.order(1); request.setOrderFrom(1);
        request.setOrderList(List.of(CheckoutMySqlFixture.item(f.product, f.property, 1), CheckoutMySqlFixture.item(otherProduct, otherProperty, 1)));
        f.service.postOrder(f.user, request);
        assertThat(f.db.jdbc.queryForObject("select count(*) from product_cart where user_id=?", Integer.class, f.user)).isZero();
        assertThat(f.db.jdbc.queryForObject("select count(*) from product_cart where cart_id=?", Integer.class, "C2" + f.suffix)).isEqualTo(1);
    }

    @Test void failedInventoryRestorationRollsBackCancellationStateAndEvent() {
        PayInfoDTO created = f.service.postOrder(f.user, f.order(1));
        // 仅破坏本测试的合成明细，模拟库存行已不存在，不能只改取消状态而漏退库存。
        f.db.jdbc.update("update order_item set property_value_id_hash=? where order_id=?", "NO_MATCHING_SYNTHETIC_SKU", created.getOrderId());
        assertThatThrownBy(() -> f.service.cancelOrder(f.user, created.getOrderId(), OrderStatusEnum.CANCELLED))
                .isInstanceOf(BusinessException.class).hasMessageContaining("回滚");
        assertThat(f.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, created.getOrderId())).isZero();
        assertThat(f.stock()).isEqualTo(4); assertThat(f.eventCount("ORDER_CANCELLED", created.getPayOrderId())).isZero();
        verify(f.payment, never()).closeOrder(anyString());
    }

    @Test void processingCanBeQueriedWithoutStartingAnotherOrder() throws Exception {
        PostOrderDTO request = f.order(1); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> f.requestService.place(f.user, CheckoutInput.normalize(request), payId -> {
                entered.countDown(); try { if (!release.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing"); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return new BigDecimal("12.34");
            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(f.service.getOrderRequest(f.user, request.getRequestId()).getRequestStatus()).isEqualTo("PROCESSING");
            assertThat(f.service.postOrder(f.user, request).getRequestStatus()).isEqualTo("PROCESSING");
            verifyNoInteractions(f.payment); assertThat(f.orderCount()).isZero();
            release.countDown(); first.get(4, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void paidOrderCannotBeCancelledOrReturnInventory() {
        PayInfoDTO created = f.service.postOrder(f.user, f.order(1));
        f.db.jdbc.update("update order_info set order_status=1 where order_id=?", created.getOrderId());
        assertThatThrownBy(() -> f.service.cancelOrder(f.user, created.getOrderId(), OrderStatusEnum.CANCELLED)).isInstanceOf(BusinessException.class);
        assertThat(f.stock()).isEqualTo(4); assertThat(f.service.getPayInfo(f.user, created.getOrderId()).getPaymentStatus()).isEqualTo("NOT_PAYABLE");
        verify(f.payment, never()).closeOrder(anyString());
    }

    @Test void addressAndSkuOwnershipAndInvalidPriceAreCheckedAgainstDatabase() {
        PostOrderDTO request = f.order(1); request.setAddressId("UNKNOWNADDR");
        assertThatThrownBy(() -> f.service.postOrder(f.user, request)).isInstanceOf(BusinessException.class);
        request.setRequestId(UUID.randomUUID().toString()); request.setAddressId(f.address);
        request.getOrderList().getFirst().setPropertyValueIds("UNKNOWN-SKU");
        assertThatThrownBy(() -> f.service.postOrder(f.user, request)).isInstanceOf(BusinessException.class);
        PostOrderDTO invalidPrice = f.order(1); f.db.jdbc.update("update product_sku set price=0 where product_id=?", f.product);
        assertThatThrownBy(() -> f.service.postOrder(f.user, invalidPrice)).isInstanceOf(BusinessException.class);
        assertThat(f.orderCount()).isZero(); assertThat(f.stock()).isEqualTo(5); verifyNoInteractions(f.payment);
    }

    @Test void hardCrashBeforeBusinessCommitRecoversAfterNaturalLeaseExpiry() throws Exception {
        PostOrderDTO request = f.order(1); Process child = child("before", request);
        try {
            assertThat(child.waitFor(18, TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).isEqualTo(24);
            assertThat(f.requests.find(f.user, request.getRequestId()).getStatus()).isEqualTo("PROCESSING");
            assertThat(f.orderCount()).isZero(); assertThat(f.stock()).isEqualTo(5);
            String reservedPayId = f.requests.find(f.user, request.getRequestId()).getPayOrderId();
            await(() -> !f.requests.find(f.user, request.getRequestId()).isLeaseActive(), 6000);
            assertThat(f.service.postOrder(f.user, request).getPayOrderId()).isEqualTo(reservedPayId);
            assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4);
        } finally { stop(child); }
    }

    @Test void hardCrashAfterCommitBeforeResponseStillReturnsOriginalOrderOnRetry() throws Exception {
        PostOrderDTO request = f.order(1); Process child = child("after", request);
        try {
            assertThat(child.waitFor(18, TimeUnit.SECONDS)).isTrue(); assertThat(child.exitValue()).isEqualTo(25);
            var saved = f.requests.find(f.user, request.getRequestId()); assertThat(saved.getStatus()).isEqualTo("CREATED");
            PayInfoDTO retry = f.service.postOrder(f.user, request); assertThat(retry.getPayOrderId()).isEqualTo(saved.getPayOrderId());
            assertThat(f.orderCount()).isEqualTo(1); assertThat(f.stock()).isEqualTo(4); assertThat(f.eventCount("ORDER_CREATED", saved.getPayOrderId())).isEqualTo(1);
        } finally { stop(child); }
    }

    private OrderItem stockChange(int count) { OrderItem item = new OrderItem(); item.setProductId(f.product); item.setPropertyValueIdHash(f.skuHash); item.setBuyCount(count); return item; }
    private <T> List<T> parallel(int count, Job<T> job) throws Exception {
        CountDownLatch go = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) { int index = i; tasks.add(pool.submit(() -> { go.await(); return job.run(index); })); }
            go.countDown(); List<T> results = new ArrayList<>(); for (Future<T> task : tasks) results.add(task.get(15, TimeUnit.SECONDS)); return results;
        } finally { go.countDown(); pool.shutdownNow(); }
    }
    private Process child(String stage, PostOrderDTO request) throws Exception {
        Path directory = Path.of("target/step08").toAbsolutePath(); Files.createDirectories(directory); String id = UUID.randomUUID().toString();
        List<String> args = new ArrayList<>(List.of("-cp", quote(System.getProperty("java.class.path")), "-Dtask.mysql.integration=true", "-Dorder.mysql.integration=true",
                quote("-Dtask.mysql.env-file=" + f.db.envFile), "com.easymall.task.CheckoutCrashProbe", stage, f.user, f.address, f.product, f.property, request.getRequestId()));
        Path file = directory.resolve(id + ".args"); Files.write(file, args, StandardCharsets.UTF_8);
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "@" + file)
                .redirectErrorStream(true).redirectOutput(directory.resolve(id + ".log").toFile()).start();
    }
    private static String quote(String value) { return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\""; }
    private static void stop(Process process) throws Exception { if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } } }
    private static void await(BooleanSupplier check, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!check.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Condition did not become true"); Thread.sleep(25); }
    }
    /** 【仅用于测试】并发模拟购买/取消的可抛异常动作。 */
    @FunctionalInterface private interface Job<T> { T run(int index) throws Exception; }
}
