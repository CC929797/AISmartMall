package com.easymall.component;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.refund.RefundPendingException;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import com.easymall.hander.PaidOrderHandler;
import com.easymall.task.TaskDeferredException;
import com.easymall.task.TaskExecution;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 【仅用于测试】模拟物流依赖，验证失败重试、已发货跳过和未决退款延后。 */
class PaidOrderHandlerTest {
    private final AppConfig config = new AppConfig();
    private final OrderInfoService orders = mock(OrderInfoService.class);
    private final OrderLogisticsInfoService logistics = mock(OrderLogisticsInfoService.class);
    private final PaidOrderHandler handler = new PaidOrderHandler(config, orders, logistics);
    private final TaskExecution task = new TaskExecution("T1", "ORDER_PAID", "PAY1", 0, "{}", 1, () -> true);

    @Test void disabledSimulationHandsOffToManualFulfillment() {
        ReflectionTestUtils.setField(config, "logisticsMode", "disabled");
        handler.handle(task);
        verifyNoInteractions(orders, logistics);
    }

    @Test void failureCanBeRetriedAndAlreadyShippedIsSkipped() {
        OrderLogisticsInfo shipment = pendingShipment();
        doThrow(new IllegalStateException("synthetic failure"))
                .doAnswer(call -> {
                    shipment.setLogisticsStatus(1);
                    return null;
                }).when(logistics).delivery(any());
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(IllegalStateException.class);
        handler.handle(task);
        handler.handle(task);
        verify(logistics, times(2)).delivery(any());
    }

    @Test void pendingRefundDefersShipment() {
        pendingShipment();
        doThrow(new RefundPendingException()).when(logistics).delivery(any());
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(TaskDeferredException.class);
    }

    private OrderLogisticsInfo pendingShipment() {
        ReflectionTestUtils.setField(config, "logisticsMode", "simulated");
        OrderInfo order = new OrderInfo();
        order.setOrderId("ORDER1");
        order.setOrderStatus(1);
        when(orders.findListByParam(any())).thenReturn(List.of(order));
        OrderLogisticsInfo shipment = new OrderLogisticsInfo();
        shipment.setLogisticsStatus(0);
        when(logistics.getOrderLogisticsInfoByOrderId("ORDER1")).thenReturn(shipment);
        return shipment;
    }
}
