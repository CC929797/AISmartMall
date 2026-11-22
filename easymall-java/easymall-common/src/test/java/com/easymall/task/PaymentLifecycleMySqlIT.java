package com.easymall.task;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.exception.BusinessException;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 【仅用于测试】使用隔离 MySQL 执行真实事务，支付渠道和 Redis 使用替身。 */
@EnabledIfSystemProperty(named = "order.mysql.integration", matches = "true")
@Timeout(20)
class PaymentLifecycleMySqlIT {
    private CheckoutMySqlFixture fixture;

    @BeforeEach void open() throws Exception {
        fixture = new CheckoutMySqlFixture();
    }

    @AfterEach void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test void repeatedPaymentAndConcurrentConfirmationCreateOneEvent() throws Exception {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        PayOrderNotifyDTO confirmed = payment(order, "SYNTHETIC_TRADE");
        try (var executor = Executors.newFixedThreadPool(5)) {
            var submissions = executor.invokeAll(
                    java.util.Collections.nCopies(10, () -> {
                        fixture.service.payOrderSuccess(confirmed);
                        return true;
                    }));
            for (var submission : submissions) {
                assertThat(submission.get()).isEqualTo(true);
            }
        }
        assertThat(status(order)).isEqualTo(1);
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isEqualTo(1);
        assertThatThrownBy(() -> fixture.service.payOrderSuccess(payment(order, "DIFFERENT_TRADE")))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.stock()).isEqualTo(4);
    }

    @Test void paymentWinsWhileGatewayCloseIsInFlight() throws Exception {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(fixture.payment.closeOrder(order.getPayOrderId())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
            return PaymentCloseResult.UNKNOWN;
        });
        var confirmed = payment(order, "WINNING_PAYMENT");
        when(fixture.payment.queryPayment(order.getPayOrderId())).thenReturn(PaymentQueryResult.paid(confirmed));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var close = executor.submit(() -> fixture.service.cancelOrder(fixture.user, order.getOrderId(), OrderStatusEnum.CANCELLED));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(status(order)).isEqualTo(8);
                assertThat(fixture.stock()).isEqualTo(4);
                assertThat(fixture.service.getPayInfo(fixture.user, order.getOrderId()).getPaymentStatus()).isEqualTo("NOT_PAYABLE");
                fixture.service.payOrderSuccess(confirmed);
            } finally {
                released.countDown();
            }
            assertThat(close.get(5, TimeUnit.SECONDS)).isEqualTo(OrderStatusEnum.PAID);
        }
        assertThat(status(order)).isEqualTo(1);
        assertThat(fixture.stock()).isEqualTo(4);
        assertThat(fixture.eventCount("ORDER_CANCELLED", order.getPayOrderId())).isZero();
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isEqualTo(1);
    }

    @Test void missingTradeDoesNotReleaseStockAndDueScanCanFinishClosure() {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(2));
        when(fixture.payment.queryPayment(order.getPayOrderId())).thenReturn(
                PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND),
                PaymentQueryResult.of(PaymentQueryResult.Status.CLOSED_UNPAID));
        assertThat(fixture.service.cancelOrder(fixture.user, order.getOrderId(), OrderStatusEnum.CANCELLED))
                .isEqualTo(OrderStatusEnum.CANCEL_PENDING);
        assertThat(fixture.stock()).isEqualTo(3);
        fixture.db.jdbc.update("update order_info set payment_close_check_at=UTC_TIMESTAMP(3) where pay_order_id=?", order.getPayOrderId());
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(4);
        assertThat(fixture.stock()).isEqualTo(5);
        assertThat(fixture.service.cancelOrder(fixture.user, order.getOrderId(), OrderStatusEnum.CANCELLED))
                .isEqualTo(OrderStatusEnum.CANCELLED);
        assertThatThrownBy(() -> fixture.service.payOrderSuccess(payment(order, "UNEXPECTED_LATE_TRADE")))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isZero();
        assertThat(fixture.stock()).isEqualTo(5);
    }

    @Test void generatedButUndeliveredLinkCannotRacePastCancellation() throws Exception {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        // 模拟此前生成失败、客户端从未收到表单。后续重试在网络阶段暂停。
        fixture.db.jdbc.update("update order_info set payment_link_issued=0 where pay_order_id=?", order.getPayOrderId());
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(fixture.payment.getPayUrl(any(), anyString(), anyString(), any(), any(Instant.class))).thenAnswer(call -> {
            entered.countDown();
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
            return new PayInfoDTO("<form>synthetic</form>", order.getPayOrderId(), order.getAmount());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var link = executor.submit(() -> fixture.service.getPayInfo(fixture.user, order.getOrderId()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.service.cancelOrder(fixture.user, order.getOrderId(), OrderStatusEnum.CANCELLED))
                        .isEqualTo(OrderStatusEnum.CANCELLED);
            } finally {
                released.countDown();
            }
            assertThat(link.get(5, TimeUnit.SECONDS).getPayInfo()).isNull();
        }
        verify(fixture.payment, never()).closeOrder(order.getPayOrderId());
        assertThat(fixture.stock()).isEqualTo(5);
    }

    @Test void expiredOrderWithNoIssuedLinkClosesFromDatabaseWithoutRedis() {
        when(fixture.payment.getPayUrl(any(), anyString(), anyString(), any(), any())).thenReturn(null);
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        fixture.db.jdbc.update("update order_info set payment_expires_at=UTC_TIMESTAMP(3) - INTERVAL 1 SECOND where pay_order_id=?",
                order.getPayOrderId());
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(5);
        assertThat(fixture.stock()).isEqualTo(5);
        verify(fixture.payment, never()).closeOrder(order.getPayOrderId());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatusEnum.class, names = {"CANCELLED", "CLOSED"})
    void expiredMissingTradeFinishesManualAndTimedClosuresExactlyOnce(OrderStatusEnum target) {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(2));
        when(fixture.payment.supportsClosingExpiredMissingTrades()).thenReturn(true);
        when(fixture.payment.closeOrder(order.getPayOrderId())).thenReturn(PaymentCloseResult.NOT_FOUND);
        when(fixture.payment.queryPayment(order.getPayOrderId()))
                .thenReturn(PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND));

        OrderStatusEnum pending = OrderStatusEnum.CLOSE_PENDING;
        if (target == OrderStatusEnum.CANCELLED) {
            pending = OrderStatusEnum.CANCEL_PENDING;
            assertThat(fixture.service.cancelOrder(fixture.user, order.getOrderId(), target)).isEqualTo(pending);
            assertThat(fixture.stock()).isEqualTo(3);
        }

        // 即使两个接口都查无此单，进入宽限期后仍保留库存。
        setPaymentExpiry(order, 30);
        makeCloseDue(order);
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(pending.getStatus());
        assertThat(fixture.stock()).isEqualTo(3);
        assertThat(fixture.eventCount("ORDER_CANCELLED", order.getPayOrderId())).isZero();

        setPaymentExpiry(order, 120);
        makeCloseDue(order);
        fixture.service.reconcilePaymentClosures();
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(target.getStatus());
        assertThat(fixture.stock()).isEqualTo(5);
        assertThat(fixture.eventCount("ORDER_CANCELLED", order.getPayOrderId())).isEqualTo(1);
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isZero();
    }

    @Test void expiredFormStaysPendingWhenEitherGatewayResultIsUncertain() {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        setPaymentExpiry(order, 120);
        when(fixture.payment.supportsClosingExpiredMissingTrades()).thenReturn(true);
        when(fixture.payment.closeOrder(order.getPayOrderId()))
                .thenReturn(PaymentCloseResult.UNKNOWN, PaymentCloseResult.NOT_FOUND);
        when(fixture.payment.queryPayment(order.getPayOrderId())).thenReturn(
                PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND),
                PaymentQueryResult.of(PaymentQueryResult.Status.UNKNOWN));

        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(OrderStatusEnum.CLOSE_PENDING.getStatus());
        makeCloseDue(order);
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(OrderStatusEnum.CLOSE_PENDING.getStatus());
        assertThat(fixture.stock()).isEqualTo(4);
        assertThat(fixture.eventCount("ORDER_CANCELLED", order.getPayOrderId())).isZero();
    }

    @Test void missingTradeFallbackRequiresSupportedChannelAndKnownDeadline() {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        setPaymentExpiry(order, 120);
        when(fixture.payment.closeOrder(order.getPayOrderId())).thenReturn(PaymentCloseResult.NOT_FOUND);
        when(fixture.payment.queryPayment(order.getPayOrderId()))
                .thenReturn(PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND));
        assertThat(fixture.service.cancelOrder(fixture.user, order.getOrderId(), OrderStatusEnum.CANCELLED))
                .isEqualTo(OrderStatusEnum.CANCEL_PENDING);

        when(fixture.payment.supportsClosingExpiredMissingTrades()).thenReturn(true);
        fixture.db.jdbc.update("update order_info set payment_expires_at=null where pay_order_id=?", order.getPayOrderId());
        makeCloseDue(order);
        fixture.service.reconcilePaymentClosures();
        assertThat(status(order)).isEqualTo(OrderStatusEnum.CANCEL_PENDING.getStatus());
        assertThat(fixture.stock()).isEqualTo(4);
        assertThat(fixture.eventCount("ORDER_CANCELLED", order.getPayOrderId())).isZero();
    }

    @Test void outboxFailureRollsBackPaymentAndWorkerRetryKeepsSameEvent() {
        PayInfoDTO order = fixture.service.postOrder(fixture.user, fixture.order(1));
        PayOrderNotifyDTO confirmed = payment(order, "ATOMIC_PAYMENT");
        BusinessTaskService broken = mock(BusinessTaskService.class);
        when(broken.enqueue(any())).thenThrow(new IllegalStateException("synthetic outbox failure"));
        ReflectionTestUtils.setField(fixture.service, "businessTaskService", broken);
        assertThatThrownBy(() -> fixture.service.payOrderSuccess(confirmed)).isInstanceOf(IllegalStateException.class);
        assertThat(status(order)).isZero();
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isZero();
        ReflectionTestUtils.setField(fixture.service, "businessTaskService", fixture.db.service);
        fixture.service.payOrderSuccess(confirmed);
        var before = fixture.db.jdbc.queryForObject("select task_id from business_task where task_type='ORDER_PAID' and biz_key=?",
                String.class, order.getPayOrderId());
        // 真实 MySQL 验证任务退避。只领取本测试记录，其他用例的事件保留原样。
        fixture.db.jdbc.update("update business_task set next_run_at=UTC_TIMESTAMP(6) - INTERVAL 1 DAY where task_id=?", before);
        BusinessTask first = fixture.db.service.claimBatch(Set.of("ORDER_PAID"), 1, 10).getFirst();
        assertThat(first.getTaskId()).isEqualTo(before);
        assertThat(fixture.db.service.defer(first)).isTrue();
        assertThat(fixture.db.service.findById(before).getAttempts()).isZero();
        fixture.db.jdbc.update("update business_task set next_run_at=UTC_TIMESTAMP(6) - INTERVAL 1 DAY where task_id=?", before);
        BusinessTask retry = fixture.db.service.claimBatch(Set.of("ORDER_PAID"), 1, 10).getFirst();
        assertThat(retry.getTaskId()).isEqualTo(before);
        assertThat(fixture.db.service.succeed(retry)).isTrue();
        assertThat(fixture.eventCount("ORDER_PAID", order.getPayOrderId())).isEqualTo(1);
    }

    private int status(PayInfoDTO order) {
        return fixture.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, order.getOrderId());
    }

    private void setPaymentExpiry(PayInfoDTO order, int secondsAgo) {
        fixture.db.jdbc.update("update order_info set payment_expires_at=TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)) where pay_order_id=?",
                -secondsAgo, order.getPayOrderId());
    }

    private void makeCloseDue(PayInfoDTO order) {
        fixture.db.jdbc.update("update order_info set payment_close_check_at=UTC_TIMESTAMP(3) where pay_order_id=?",
                order.getPayOrderId());
    }

    private static PayOrderNotifyDTO payment(PayInfoDTO order, String tradeId) {
        var confirmed = new PayOrderNotifyDTO(order.getPayOrderId(), tradeId);
        confirmed.setAmount(order.getAmount());
        return confirmed;
    }
}
