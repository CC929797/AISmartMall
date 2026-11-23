package com.easymall.task;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.LogisticsStatusEnum;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.mappers.OrderItemMapper;
import com.easymall.mappers.OrderLogisticsInfoMapper;
import com.easymall.mappers.OrderLogisticsInfoRecordMapper;
import com.easymall.mappers.SearchSyncMapper;
import com.easymall.service.Impl.OrderLogisticsInfoServiceImpl;
import com.easymall.sync.SearchSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 仅用于测试。固定隔离 MySQL，真实执行版本、任务、业务事务 SQL，支付和外部索引不参与。 */
@EnabledIfSystemProperty(named = "order.mysql.integration", matches = "true")
class SearchSyncFulfillmentMySqlIT {
    private CheckoutMySqlFixture fixture;
    private SearchSyncService sync;
    private SearchSyncMapper states;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CheckoutMySqlFixture();
        if (fixture.db.jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=database() AND table_name='search_sync_state'
                """, Integer.class) == 0) {
            var path = fixture.db.envFile.getParent().getParent().getParent()
                    .resolve("easymall-java/db/migration/V20261122_01__search_sync_state.sql");
            try (var connection = fixture.db.dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
            }
        }
        states = fixture.session.getMapper(SearchSyncMapper.class);
        sync = new SearchSyncService(states, fixture.db.service);
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void businessRollbackRemovesProjectionVersionAndBothTasks() {
        // 故意让业务事务失败，核对同步状态和任务行随商品修改一起回滚。
        assertThatThrownBy(() -> fixture.db.transaction.executeWithoutResult(status -> {
            fixture.db.jdbc.update("UPDATE product_info SET product_name='version-that-rolls-back' WHERE product_id=?", fixture.product);
            sync.productChanged(fixture.product);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(fixture.db.jdbc.queryForObject("SELECT product_name FROM product_info WHERE product_id=?", String.class,
                fixture.product)).isNotEqualTo("version-that-rolls-back");
        assertThat(states.find(SearchSyncService.PRODUCT_SEARCH, fixture.product)).isNull();
        assertThat(states.find(SearchSyncService.PRODUCT_VECTOR, fixture.product)).isNull();
        assertThat(fixture.eventCount(SearchSyncService.PRODUCT_SEARCH, fixture.product)).isZero();
        assertThat(fixture.eventCount(SearchSyncService.PRODUCT_VECTOR, fixture.product)).isZero();
    }

    @Test
    void activeLeaseSerializesVersionsAndOldWriteCannotPublish() {
        // 模拟旧任务正在外部写入时产生新版本，检查租约占用和发布条件。
        fixture.db.transaction.executeWithoutResult(status -> sync.salesChanged(fixture.product));
        BusinessTask first = claimSearch(1);
        var second = new BusinessTask[1];
        sync.synchronize(execution(first), () -> {
            // 模拟旧版正在调用外部服务时，管理员提交了新的业务版本。
            fixture.db.transaction.executeWithoutResult(status -> sync.salesChanged(fixture.product));
            second[0] = claimSearch(2);
            assertThatThrownBy(() -> sync.synchronize(execution(second[0]), () -> {
                throw new AssertionError("second version must wait for the active lease");
            })).isInstanceOf(TaskDeferredException.class);
        });
        assertThat(states.find(SearchSyncService.PRODUCT_SEARCH, fixture.product).publishedVersion()).isZero();
        assertThat(fixture.db.service.succeed(first)).isTrue();

        sync.synchronize(execution(second[0]), () -> {});
        assertThat(states.find(SearchSyncService.PRODUCT_SEARCH, fixture.product).publishedVersion()).isEqualTo(2);
        assertThat(fixture.db.service.succeed(second[0])).isTrue();
    }

    @Test
    void failedExternalWriteStaysUnpublishedAndCanBeRetried() {
        // 用异常模拟外部服务失败，再通过真实 MySQL 任务接口验证原任务可继续处理。
        fixture.db.transaction.executeWithoutResult(status -> sync.salesChanged(fixture.product));
        BusinessTask first = claimSearch(1);
        assertThatThrownBy(() -> sync.synchronize(execution(first), () -> {
            throw new IllegalStateException("synthetic ES unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(states.find(SearchSyncService.PRODUCT_SEARCH, fixture.product).publishedVersion()).isZero();
        assertThat(fixture.db.service.fail(first, "SYNTHETIC_ES_DOWN")).isTrue();

        BusinessTask retry = claimSearch(1);
        assertThat(retry.getTaskId()).isEqualTo(first.getTaskId());
        assertThat(retry.getAttempts()).isEqualTo(2);
        sync.synchronize(execution(retry), () -> {});
        assertThat(fixture.db.service.succeed(retry)).isTrue();
        assertThat(states.find(SearchSyncService.PRODUCT_SEARCH, fixture.product).publishedVersion()).isEqualTo(1);
    }

    @Test
    void deliveryAndProgressResumeWithoutDuplicateRecordsOrSales() {
        // 重复执行模拟物流和收货入口，分别检查轨迹和销量只增加一次。
        var order = fixture.service.postOrder(fixture.user, fixture.order(1));
        var payment = new PayOrderNotifyDTO(order.getPayOrderId(), "SYNTHETIC-11B-" + fixture.suffix);
        payment.setAmount(order.getAmount());
        fixture.service.payOrderSuccess(payment);
        var logistics = new OrderLogisticsInfoServiceImpl();
        for (Class<?> type : List.of(OrderInfoMapper.class, OrderLogisticsInfoMapper.class,
                OrderLogisticsInfoRecordMapper.class, OrderItemMapper.class)) {
            String name = type.getSimpleName();
            ReflectionTestUtils.setField(logistics, Character.toLowerCase(name.charAt(0)) + name.substring(1),
                    fixture.session.getMapper(type));
        }
        var config = mock(AppConfig.class);
        when(config.isSimulatedLogistics()).thenReturn(true);
        when(config.getOrderConfirmMinute()).thenReturn(15);
        ReflectionTestUtils.setField(logistics, "fulfillmentTasks", new FulfillmentTasks(fixture.db.service));
        ReflectionTestUtils.setField(logistics, "appConfig", config);
        ReflectionTestUtils.setField(fixture.service, "appConfig", config);
        ReflectionTestUtils.setField(fixture.service, "searchSyncService", sync);

        String orderId = order.getOrderId();
        var shipment = new OrderLogisticsInfo();
        shipment.setOrderId(orderId);
        shipment.setLogisticsCompany("合成物流");
        shipment.setLogisticsNo("SYNTHETIC-" + fixture.suffix);

        assertThatThrownBy(() -> fixture.db.transaction.executeWithoutResult(status -> {
            logistics.delivery(shipment);
            throw new IllegalStateException("synthetic shipment rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.eventCount(FulfillmentTasks.LOGISTICS_STEP, orderId)).isZero();
        assertThat(fixture.eventCount(FulfillmentTasks.AUTO_CONFIRM, orderId)).isZero();

        fixture.db.transaction.executeWithoutResult(status -> logistics.delivery(shipment));
        assertThat(fixture.eventCount(FulfillmentTasks.AUTO_CONFIRM, orderId)).isEqualTo(1);

        for (int completed = 1; completed <= 5; completed++) {
            int version = completed;
            fixture.db.transaction.executeWithoutResult(status -> logistics.mockOrderLogistics(orderId, version));
            fixture.db.transaction.executeWithoutResult(status -> logistics.mockOrderLogistics(orderId, version));
        }
        assertThat(fixture.eventCount(FulfillmentTasks.LOGISTICS_STEP, orderId)).isEqualTo(5);
        assertThat(fixture.db.jdbc.queryForObject("SELECT COUNT(*) FROM order_logistics_info_record WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(6);
        assertThat(fixture.db.jdbc.queryForObject("SELECT logistics_status FROM order_logistics_info WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(LogisticsStatusEnum.DELIVERED.getStatus());

        fixture.db.transaction.executeWithoutResult(status -> fixture.service.confirmSimulatedOrder(orderId));
        fixture.db.transaction.executeWithoutResult(status -> fixture.service.confirmSimulatedOrder(orderId));
        assertThat(fixture.db.jdbc.queryForObject("SELECT order_status FROM order_info WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(3);
        assertThat(fixture.db.jdbc.queryForObject("SELECT total_sale FROM product_info WHERE product_id=?",
                Integer.class, fixture.product)).isEqualTo(1);
        assertThat(fixture.eventCount(SearchSyncService.PRODUCT_SEARCH, fixture.product)).isEqualTo(1);
    }

    private BusinessTask claimSearch(long version) {
        fixture.db.jdbc.update("""
                UPDATE business_task SET next_run_at='2000-01-01 00:00:00'
                WHERE task_type=? AND biz_key=? AND biz_version=?
                """, SearchSyncService.PRODUCT_SEARCH, fixture.product, version);
        var task = fixture.db.service.claimBatch(Set.of(SearchSyncService.PRODUCT_SEARCH), 1, 30).getFirst();
        assertThat(task.getBizKey()).isEqualTo(fixture.product);
        assertThat(task.getBizVersion()).isEqualTo(version);
        return task;
    }

    private TaskExecution execution(BusinessTask task) {
        return new TaskExecution(task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getBizVersion(),
                task.getPayload(), task.getAttempts(), () -> fixture.db.service.ownsLease(task), task.getLeaseOwner());
    }
}
