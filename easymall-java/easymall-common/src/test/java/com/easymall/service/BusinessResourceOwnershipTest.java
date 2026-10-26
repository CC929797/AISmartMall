package com.easymall.service;

import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.*;
import com.easymall.entity.query.*;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.*;
import com.easymall.service.Impl.OrderCommentServiceImpl;
import com.easymall.service.Impl.OrderInfoServiceImpl;
import com.easymall.service.Impl.OrderLogisticsInfoServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证业务服务已有的资源归属检查，不调用真实支付或其他基础设施。 */
@SuppressWarnings("unchecked")
class BusinessResourceOwnershipTest {
    private final OrderInfoMapper<OrderInfo, OrderInfoQuery> orders = mock(OrderInfoMapper.class);
    private final OrderItemMapper<OrderItem, OrderItemQuery> items = mock(OrderItemMapper.class);

    @Test
    void aCannotCancelBsOrder() {
        when(orders.selectByOrderId("B-ORDER")).thenReturn(bsOrder());
        OrderInfoServiceImpl service = orderService();

        assertThatThrownBy(() -> service.cancelOrder("A", "B-ORDER", OrderStatusEnum.CANCELLED))
                .isInstanceOf(BusinessException.class);

        verify(orders, never()).selectList(any());
        verify(orders, never()).updateByParam(any(), any());
        verifyNoInteractions(items);
    }

    @Test
    void aCannotDeleteBsOrder() {
        when(orders.selectByOrderId("B-ORDER")).thenReturn(bsOrder());

        assertThatThrownBy(() -> orderService().deleteOrder("A", "B-ORDER"))
                .isInstanceOf(BusinessException.class);
        verify(orders, never()).updateByParam(any(), any());
    }

    @Test
    void aCannotRefundBsOrderItem() {
        OrderItem item = new OrderItem();
        item.setOrderId("B-ORDER");
        when(items.selectByOrderItemId("B-ITEM")).thenReturn(item);
        when(orders.selectByOrderId("B-ORDER")).thenReturn(bsOrder());

        assertThatThrownBy(() -> orderService().refundByOrderItemId("A", "B-ITEM"))
                .isInstanceOf(BusinessException.class);
        verify(items, never()).updateByParam(any(), any());
        verify(orders, never()).updateByParam(any(), any());
    }

    @Test
    void confirmingAnOrderAlwaysIncludesTheAuthenticatedOwner() {
        when(orders.updateByParam(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService().confirmOrder("A", "B-ORDER"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<OrderInfoQuery> query = ArgumentCaptor.forClass(OrderInfoQuery.class);
        verify(orders).updateByParam(any(), query.capture());
        assertThat(query.getValue().getUserId()).isEqualTo("A");
        assertThat(query.getValue().getOrderId()).isEqualTo("B-ORDER");
        verifyNoInteractions(items);
    }

    @Test
    void aCannotPostACommentForBsOrder() {
        assertForeignCommentRejected(false);
    }

    @Test
    void aCannotAppendACommentForBsOrder() {
        assertForeignCommentRejected(true);
    }

    @Test
    void aCannotReadBsPrivateLogisticsRecords() {
        OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> logistics = mock(OrderLogisticsInfoMapper.class);
        OrderLogisticsInfoRecordMapper<OrderLogisticsInfoRecord, OrderLogisticsInfoRecordQuery> records =
                mock(OrderLogisticsInfoRecordMapper.class);
        OrderLogisticsInfo foreign = new OrderLogisticsInfo();
        foreign.setUserId("B");
        when(logistics.selectByOrderId("B-ORDER")).thenReturn(foreign);
        OrderLogisticsInfoServiceImpl service = new OrderLogisticsInfoServiceImpl();
        ReflectionTestUtils.setField(service, "orderLogisticsInfoMapper", logistics);
        ReflectionTestUtils.setField(service, "orderLogisticsInfoRecordMapper", records);

        assertThatThrownBy(() -> service.getOrderLogisticsRecords("A", "B-ORDER"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(records);
    }

    private void assertForeignCommentRejected(boolean append) {
        OrderCommentMapper<OrderComment, OrderCommentQuery> comments = mock(OrderCommentMapper.class);
        OrderCommentServiceImpl service = new OrderCommentServiceImpl();
        ReflectionTestUtils.setField(service, "orderInfoMapper", orders);
        ReflectionTestUtils.setField(service, "orderCommentMapper", comments);
        ReflectionTestUtils.setField(service, "orderItemMapper", items);
        when(orders.selectByOrderId("B-ORDER")).thenReturn(bsOrder());

        assertThatThrownBy(() -> {
            if (append) service.postReComment("A", "B-ORDER", "synthetic append", null);
            else service.postComment("A", "B-ORDER", "synthetic review", null, 5);
        }).isInstanceOf(BusinessException.class);
        verifyNoInteractions(comments, items);
        verify(orders, never()).updateByParam(any(), any());
    }

    private OrderInfoServiceImpl orderService() {
        OrderInfoServiceImpl service = new OrderInfoServiceImpl();
        ReflectionTestUtils.setField(service, "orderInfoMapper", orders);
        ReflectionTestUtils.setField(service, "orderItemMapper", items);
        return service;
    }

    private static OrderInfo bsOrder() {
        OrderInfo order = new OrderInfo();
        order.setOrderId("B-ORDER");
        order.setUserId("B");
        order.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
        return order;
    }
}
