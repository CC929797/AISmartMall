package com.easymall.controller;

import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.OrderComment;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.exception.BusinessException;
import com.easymall.service.OrderCommentService;
import com.easymall.service.OrderInfoService;
import com.easymall.service.UserAddressService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证控制器使用服务端认证身份检查资源归属，并拒绝未实际修改记录的写操作。 */
class UserResourceBoundaryTest {
    @Test
    void aCannotReadBsPaidOrder() {
        OrderInfoService service = mock(OrderInfoService.class);
        OrderInfo order = new OrderInfo();
        order.setUserId("B");
        when(service.findListByParam(any())).thenReturn(List.of(order));
        OrderController controller = new OrderController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return userA(); }
        };
        ReflectionTestUtils.setField(controller, "orderInfoService", service);

        assertThatThrownBy(() -> controller.getOrderInfo("B-PAY-ORDER"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aCannotReadBsComment() {
        OrderCommentService service = mock(OrderCommentService.class);
        OrderComment comment = new OrderComment();
        comment.setUserId("B");
        when(service.getOrderCommentByOrderId("B-ORDER")).thenReturn(comment);

        assertThatThrownBy(() -> commentController(service).getComment("B-ORDER"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingCommentHasTheSameRejectionAsAForeignComment() {
        assertThatThrownBy(() -> commentController(mock(OrderCommentService.class)).getComment("MISSING"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aCannotDeleteBsAddressAndTheQueryAlwaysUsesA() {
        UserAddressService service = mock(UserAddressService.class);
        when(service.deleteByParam(any())).thenReturn(0);
        UserAddressController controller = new UserAddressController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return userA(); }
        };
        ReflectionTestUtils.setField(controller, "userAddressService", service);

        assertThatThrownBy(() -> controller.delAddress("B-ADDRESS"))
                .isInstanceOf(BusinessException.class);
        ArgumentCaptor<UserAddressQuery> query = ArgumentCaptor.forClass(UserAddressQuery.class);
        verify(service).deleteByParam(query.capture());
        assertThat(query.getValue().getAddressId()).isEqualTo("B-ADDRESS");
        assertThat(query.getValue().getUserId()).isEqualTo("A");
    }

    @Test
    void aCannotDeleteBsCommentAndTheQueryAlwaysUsesA() {
        OrderCommentService service = mock(OrderCommentService.class);
        when(service.updateByParam(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> commentController(service).delMyComment("B-ORDER"))
                .isInstanceOf(BusinessException.class);
        ArgumentCaptor<OrderCommentQuery> query = ArgumentCaptor.forClass(OrderCommentQuery.class);
        verify(service).updateByParam(any(), query.capture());
        assertThat(query.getValue().getOrderId()).isEqualTo("B-ORDER");
        assertThat(query.getValue().getUserId()).isEqualTo("A");
    }

    @Test
    void aCanReadAsOwnComment() {
        OrderCommentService service = mock(OrderCommentService.class);
        OrderComment comment = new OrderComment();
        comment.setUserId("A");
        when(service.getOrderCommentByOrderId("A-ORDER")).thenReturn(comment);

        assertThat(commentController(service).getComment("A-ORDER").getData()).isSameAs(comment);
    }

    @Test
    void aCanDeleteAsOwnCommentWhenOneOwnedRowWasChanged() {
        OrderCommentService service = mock(OrderCommentService.class);
        when(service.updateByParam(any(), any())).thenReturn(1);

        assertThat(commentController(service).delMyComment("A-ORDER").getCode()).isEqualTo(200);
    }

    @Test
    void omittingTheCommentIdCannotTurnADeleteIntoABulkUpdate() {
        OrderCommentService service = mock(OrderCommentService.class);

        assertThatThrownBy(() -> commentController(service).delMyComment(null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }

    @Test
    void aCanDeleteAsOwnAddressWhenOneOwnedRowWasDeleted() {
        UserAddressService service = mock(UserAddressService.class);
        when(service.deleteByParam(any())).thenReturn(1);
        UserAddressController controller = addressController(service);

        assertThat(controller.delAddress("A-ADDRESS").getCode()).isEqualTo(200);
    }

    @Test
    void omittingTheAddressIdCannotTurnADeleteIntoABulkDelete() {
        UserAddressService service = mock(UserAddressService.class);

        assertThatThrownBy(() -> addressController(service).delAddress(null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }

    private static UserAddressController addressController(UserAddressService service) {
        UserAddressController controller = new UserAddressController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return userA(); }
        };
        ReflectionTestUtils.setField(controller, "userAddressService", service);
        return controller;
    }

    private static OrderCommentController commentController(OrderCommentService service) {
        OrderCommentController controller = new OrderCommentController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() { return userA(); }
        };
        ReflectionTestUtils.setField(controller, "orderCommentService", service);
        return controller;
    }

    private static TokenUserInfoDTO userA() {
        TokenUserInfoDTO user = new TokenUserInfoDTO();
        user.setUserId("A");
        return user;
    }
}
