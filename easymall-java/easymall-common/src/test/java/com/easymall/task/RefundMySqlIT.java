package com.easymall.task;

import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.mappers.OrderLogisticsInfoMapper;
import com.easymall.mappers.OrderLogisticsInfoRecordMapper;
import com.easymall.mappers.RefundRequestMapper;
import com.easymall.refund.RefundChannelResult;
import com.easymall.refund.RefundPendingException;
import com.easymall.refund.RefundService;
import com.easymall.refund.RefundStatus;
import com.easymall.refund.RefundView;
import com.easymall.service.Impl.OrderLogisticsInfoServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 【仅用于测试】真实隔离 MySQL；渠道调用使用模拟对象。 */
@EnabledIfSystemProperty(named = "refund.mysql.integration", matches = "true")
@Timeout(30)
@SuppressWarnings("unchecked")
class RefundMySqlIT {
    private CheckoutMySqlFixture fixture;
    private RefundService refunds;

    @BeforeEach void open() throws Exception {
        fixture = new CheckoutMySqlFixture();
        if (fixture.db.jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='refund_request'", Integer.class) == 0) {
            var migration = fixture.db.envFile.getParent().getParent().getParent()
                    .resolve("easymall-java/db/migration/V20261114_01__refund_request.sql");
            try (var connection = fixture.db.dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
            }
        }
        refunds = new RefundService(fixture.session.getMapper(RefundRequestMapper.class),
                fixture.session.getMapper(OrderInfoMapper.class), fixture.items,
                fixture.session.getMapper(OrderLogisticsInfoMapper.class), fixture.skus,
                new DataSourceTransactionManager(fixture.db.dataSource));
    }

    @AfterEach void close() {
        if (fixture != null) fixture.close();
    }

    @Test void concurrentRequestsForOneItemShareOneRefundAndReturnStockOnce() throws Exception {
        String item = paidOrder(fixture.order(2)) + "_1";
        CountDownLatch reachedChannel = new CountDownLatch(1);
        CountDownLatch releaseChannel = new CountDownLatch(1);
        when(fixture.payment.refund(anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reachedChannel.countDown();
            assertThat(releaseChannel.await(8, TimeUnit.SECONDS)).isTrue();
            return RefundChannelResult.SUCCESS;
        });
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> refunds.apply(fixture.user, item, key));
            try {
                assertThat(reachedChannel.await(8, TimeUnit.SECONDS)).isTrue();
                var duplicate = executor.submit(() -> apply(item)).get(5, TimeUnit.SECONDS);
                assertThat(duplicate.status()).isEqualTo(RefundStatus.PROCESSING);
                assertThat(refunds.apply(fixture.user, item, key).refundId()).isEqualTo(duplicate.refundId());
            } finally {
                releaseChannel.countDown();
            }
            assertThat(first.get(8, TimeUnit.SECONDS).status()).isEqualTo(RefundStatus.SUCCESS);
        }
        assertThat(apply(item).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(fixture.stock()).isEqualTo(5);
        assertThat(count(item)).isEqualTo(1);
        verify(fixture.payment, times(1)).refund(anyString(), anyString(), any());
    }

    @Test void timeoutRecoversUsingOriginalChannelNumber() {
        String item = paidOrder(fixture.order(1)) + "_1";
        when(fixture.payment.refund(anyString(), anyString(), any())).thenReturn(RefundChannelResult.UNKNOWN, RefundChannelResult.SUCCESS);
        RefundView pending = apply(item);
        assertThat(pending.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(apply(item).refundId()).isEqualTo(pending.refundId());
        String payId = payId(pending);
        when(fixture.payment.queryRefund(payId, pending.refundId(), pending.amount())).thenReturn(RefundChannelResult.NOT_FOUND);
        due(pending);
        refunds.reconcileDue();
        assertThat(refunds.status(fixture.user, item).status()).isEqualTo(RefundStatus.SUCCESS);
        verify(fixture.payment, times(2)).refund(payId, pending.refundId(), pending.amount());
        verify(fixture.payment).queryRefund(payId, pending.refundId(), pending.amount());
        assertThat(count(item)).isEqualTo(1);
    }

    @Test void channelSuccessSurvivesLocalTransactionRollback() {
        String order = paidOrder(fixture.order(1));
        String item = order + "_1";
        when(fixture.payment.refund(anyString(), anyString(), any())).thenAnswer(call -> {
            fixture.db.jdbc.update("update product_sku set stock=null where product_id=?", fixture.product);
            return RefundChannelResult.SUCCESS;
        });
        RefundView pending = apply(item);
        assertThat(pending.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(itemStatus(item)).isEqualTo(2);
        assertThat(orderStatus(order)).isEqualTo(1);
        fixture.db.jdbc.update("update product_sku set stock=4 where product_id=?", fixture.product);
        when(fixture.payment.queryRefund(payId(pending), pending.refundId(), pending.amount()))
                .thenReturn(RefundChannelResult.SUCCESS);
        due(pending);
        refunds.reconcileDue();
        assertThat(refunds.status(fixture.user, item).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(orderStatus(order)).isEqualTo(6);
        assertThat(fixture.stock()).isEqualTo(5);
        verify(fixture.payment, times(1)).refund(anyString(), anyString(), any());
    }

    @Test void concurrentDifferentItemsAggregateAfterEachResult() throws Exception {
        String orderId = paidOrder(orderWithTwoSkus());
        CountDownLatch bothReserved = new CountDownLatch(2);
        AtomicInteger payments = new AtomicInteger();
        when(fixture.payment.refund(anyString(), anyString(), any())).thenAnswer(call -> {
            payments.incrementAndGet();
            bothReserved.countDown();
            assertThat(bothReserved.await(8, TimeUnit.SECONDS)).isTrue();
            return RefundChannelResult.SUCCESS;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> apply(orderId + "_1"));
            var second = executor.submit(() -> apply(orderId + "_2"));
            assertThat(first.get(12, TimeUnit.SECONDS).status()).isEqualTo(RefundStatus.SUCCESS);
            assertThat(second.get(12, TimeUnit.SECONDS).status()).isEqualTo(RefundStatus.SUCCESS);
        }
        assertThat(orderStatus(orderId)).isEqualTo(6);
        assertThat(payments.get()).isEqualTo(2);
        assertThat(fixture.stock()).isEqualTo(5);
        String secondHash = com.easymall.utils.StringTools.encodeByMD5("W" + fixture.suffix);
        assertThat(fixture.db.jdbc.queryForObject("select stock from product_sku where product_id=? and property_value_id_hash=?", Integer.class, fixture.product, secondHash)).isEqualTo(5);
    }

    @Test void shippedRefundKeepsStockAndRejectsOtherUser() {
        String order = paidOrder(fixture.order(1));
        String item = order + "_1";
        fixture.db.jdbc.update("update order_info set order_status=? where order_id=?", OrderStatusEnum.SHIPPED.getStatus(), order);
        fixture.db.jdbc.update("update order_logistics_info set logistics_status=1 where order_id=?", order);
        assertThatThrownBy(() -> refunds.apply("OTHER", item, UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        assertThat(refunds.status("OTHER", item)).isNull();
        assertThat(count(item)).isZero();
        verify(fixture.payment, never()).refund(anyString(), anyString(), any());
        when(fixture.payment.refund(anyString(), anyString(), any())).thenReturn(RefundChannelResult.SUCCESS);
        assertThat(apply(item).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(fixture.stock()).isEqualTo(4);
    }

    @Test void definiteRejectionReleasesItemButSameRequestReplaysFailure() {
        String item = paidOrder(fixture.order(1)) + "_1";
        when(fixture.payment.refund(anyString(), anyString(), any())).thenReturn(RefundChannelResult.REJECTED, RefundChannelResult.SUCCESS);
        String key = UUID.randomUUID().toString();
        RefundView failed = refunds.apply(fixture.user, item, key);
        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refunds.apply(fixture.user, item, key)).isEqualTo(failed);
        assertThat(itemStatus(item)).isEqualTo(1);
        assertThat(apply(item).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(refunds.apply(fixture.user, item, key)).isEqualTo(failed);
        assertThat(count(item)).isEqualTo(2);
    }

    @Test void pendingRefundBlocksShippingAndPartialRefundKeepsRemainingItemInFulfillment() {
        String orderId = paidOrder(orderWithTwoSkus());
        String item = orderId + "_1";
        var deliveryService = new OrderLogisticsInfoServiceImpl();
        for (Class<?> mapper : List.of(OrderInfoMapper.class, OrderLogisticsInfoMapper.class, OrderLogisticsInfoRecordMapper.class)) {
            String name = mapper.getSimpleName();
            ReflectionTestUtils.setField(deliveryService, Character.toLowerCase(name.charAt(0)) + name.substring(1), fixture.session.getMapper(mapper));
        }
        ReflectionTestUtils.setField(deliveryService, "orderItemMapper", fixture.items);
        ReflectionTestUtils.setField(deliveryService, "redisComponent", fixture.redis);
        var app = mock(com.easymall.entity.config.AppConfig.class);
        when(app.getOrderConfirmMinute()).thenReturn(15);
        ReflectionTestUtils.setField(deliveryService, "appConfig", app);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(fixture.db.dataSource));
        var shipment = new OrderLogisticsInfo();
        shipment.setOrderId(orderId);
        shipment.setLogisticsCompany("测试物流");
        shipment.setLogisticsNo("SYNTHETIC-" + fixture.suffix);

        when(fixture.payment.refund(anyString(), anyString(), any())).thenReturn(RefundChannelResult.UNKNOWN);
        RefundView pending = apply(item);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> deliveryService.delivery(shipment)))
                .isInstanceOf(RefundPendingException.class);
        when(fixture.payment.queryRefund(payId(pending), pending.refundId(), pending.amount())).thenReturn(RefundChannelResult.SUCCESS);
        due(pending);
        refunds.reconcileDue();
        assertThat(orderStatus(orderId)).isEqualTo(7);
        assertThat(fulfillmentCount(OrderStatusEnum.PAID)).isEqualTo(1);
        assertThat(fulfillmentCount(OrderStatusEnum.SHIPPED)).isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> fixture.service.confirmOrder(fixture.user, orderId)))
                .isInstanceOf(BusinessException.class);

        tx.executeWithoutResult(status -> deliveryService.delivery(shipment));
        assertThat(orderStatus(orderId)).isEqualTo(7);
        assertThat(fulfillmentCount(OrderStatusEnum.PAID)).isZero();
        assertThat(fulfillmentCount(OrderStatusEnum.SHIPPED)).isEqualTo(1);
        tx.executeWithoutResult(status -> fixture.service.confirmOrder(fixture.user, orderId));
        assertThat(orderStatus(orderId)).isEqualTo(3);
    }

    @Test void historicalRefundCannotBeRequestedAgain() {
        String order = paidOrder(fixture.order(1));
        String item = order + "_1";
        fixture.db.jdbc.update("update order_item set order_item_status=0, refund_order_id='LEGACY' where order_item_id=?", item);
        fixture.db.jdbc.update("update order_info set order_status=6 where order_id=?", order);
        assertThat(refunds.status(fixture.user, item)).isNull();
        assertThatThrownBy(() -> apply(item)).isInstanceOf(BusinessException.class);
        verify(fixture.payment, never()).refund(anyString(), anyString(), any());
        assertThat(count(item)).isZero();
    }

    private PostOrderDTO orderWithTwoSkus() {
        String secondProperty = "W" + fixture.suffix;
        String secondHash = com.easymall.utils.StringTools.encodeByMD5(secondProperty);
        fixture.db.jdbc.update("insert into product_property_value(product_id,property_id,property_name,property_value_id,property_value,sort) values (?, 'COLOR','颜色',?,'红色',2)", fixture.product, secondProperty);
        fixture.db.jdbc.update("insert into product_sku(product_id,property_value_id_hash,property_value_ids,price,stock,sort) values (?,?,?,9.00,5,2)", fixture.product, secondHash, secondProperty);
        PostOrderDTO order = fixture.order(1);
        order.setOrderList(List.of(CheckoutMySqlFixture.item(fixture.product, fixture.property, 1),
                CheckoutMySqlFixture.item(fixture.product, secondProperty, 1)));
        return order;
    }

    private int fulfillmentCount(OrderStatusEnum status) {
        OrderInfoQuery query = new OrderInfoQuery();
        query.setUserId(fixture.user);
        query.setQueryItems(true);
        query.setFulfillmentOrderStatus(status);
        List<?> orders = fixture.session.getMapper(OrderInfoMapper.class).selectList(query);
        int count = fixture.session.getMapper(OrderInfoMapper.class).selectCount(query);
        assertThat(orders).extracting(order -> ((OrderInfo) order).getOrderId()).doesNotHaveDuplicates();
        // 旧分页计数按明细计算，验证该主单是否存在于对应的履约筛选。
        assertThat(count > 0).isEqualTo(!orders.isEmpty());
        return orders.size();
    }

    private RefundView apply(String item) {
        return refunds.apply(fixture.user, item, UUID.randomUUID().toString());
    }

    private String paidOrder(PostOrderDTO input) {
        var order = fixture.service.postOrder(fixture.user, input);
        var confirmed = new PayOrderNotifyDTO(order.getPayOrderId(), "SYNTHETIC_REFUND_TRADE");
        confirmed.setAmount(order.getAmount());
        fixture.service.payOrderSuccess(confirmed);
        return order.getOrderId();
    }

    private int orderStatus(String orderId) {
        return fixture.db.jdbc.queryForObject("select order_status from order_info where order_id=?", Integer.class, orderId);
    }

    private int itemStatus(String itemId) {
        return fixture.db.jdbc.queryForObject("select order_item_status from order_item where order_item_id=?", Integer.class, itemId);
    }

    private int count(String item) {
        return fixture.db.jdbc.queryForObject("select count(*) from refund_request where order_item_id=?", Integer.class, item);
    }

    private String payId(RefundView view) {
        return fixture.session.getMapper(RefundRequestMapper.class).find(view.refundId()).payOrderId();
    }

    private void due(RefundView view) {
        fixture.db.jdbc.update("update refund_request set next_check_at=UTC_TIMESTAMP(6) where refund_id=?", view.refundId());
    }
}
