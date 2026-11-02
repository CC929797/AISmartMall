package com.easymall.service;

import com.easymall.entity.enums.OrderCommentStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderComment;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderItem;
import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.OrderItemQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.OrderCommentMapper;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.mappers.OrderItemMapper;
import com.easymall.service.Impl.OrderCommentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 评论输入先于资源查询和写操作校验，避免非 HTTP 调用绕过边界。 */
@SuppressWarnings("unchecked")
class CommentInputBoundaryTest {
    private OrderInfoMapper<OrderInfo, OrderInfoQuery> orders;
    private OrderItemMapper<OrderItem, OrderItemQuery> items;
    private OrderCommentMapper<OrderComment, OrderCommentQuery> comments;
    private OrderCommentServiceImpl service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderInfoMapper.class);
        items = mock(OrderItemMapper.class);
        comments = mock(OrderCommentMapper.class);
        service = new OrderCommentServiceImpl();
        ReflectionTestUtils.setField(service, "orderInfoMapper", orders);
        ReflectionTestUtils.setField(service, "orderItemMapper", items);
        ReflectionTestUtils.setField(service, "orderCommentMapper", comments);
    }

    record Input(String userId, String orderId, String content, String images, Integer star) {}

    static Stream<Input> invalidComments() {
        return Stream.of(
                new Input("INPUT00001", "ORDER-INPUT", null, null, 5),
                new Input("INPUT00001", "ORDER-INPUT", "", null, 5),
                new Input("INPUT00001", "ORDER-INPUT", " \t ", null, 5),
                new Input("INPUT00001", "ORDER-INPUT", "评".repeat(301), null, 5),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", null, null),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", null, 0),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", null, 6),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", "p".repeat(301), 5),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", "a,b,c,d,e,f", 5),
                new Input("INPUT00001", "ORDER-INPUT", "合成评价", "a,,b", 5),
                new Input(null, "ORDER-INPUT", "合成评价", null, 5),
                new Input("USER1234567", "ORDER-INPUT", "合成评价", null, 5),
                new Input("INPUT00001", " ", "合成评价", null, 5),
                new Input("INPUT00001", "o".repeat(33), "合成评价", null, 5));
    }

    @ParameterizedTest
    @MethodSource("invalidComments")
    void invalidInitialCommentsNeverReachAnyMapper(Input input) {
        assertThatThrownBy(() -> service.postComment(input.userId(), input.orderId(),
                input.content(), input.images(), input.star())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, items, comments);
    }

    static Stream<Input> invalidFollowUps() {
        return invalidComments().filter(input -> input.star() != null && input.star() == 5);
    }

    @ParameterizedTest
    @MethodSource("invalidFollowUps")
    void invalidFollowUpsNeverReachAnyMapper(Input input) {
        assertThatThrownBy(() -> service.postReComment(input.userId(), input.orderId(),
                input.content(), input.images())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, items, comments);
    }

    @Test
    void validMaximumContentAndFiveImagesStillCreateAnOwnedReview() {
        configureOwnedOrder(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());
        OrderItem item = new OrderItem();
        item.setProductId("PRODUCT001");
        item.setPropertyInfo("合成规格");
        when(items.selectList(any())).thenReturn(List.of(item));
        String content = "评".repeat(300);
        String images = "a.png,b.png,c.png,d.png,e.png";

        service.postComment("INPUT00001", "ORDER-INPUT", content, images, 5);

        ArgumentCaptor<OrderComment> review = ArgumentCaptor.forClass(OrderComment.class);
        verify(comments).insert(review.capture());
        assertThat(review.getValue().getUserId()).isEqualTo("INPUT00001");
        assertThat(review.getValue().getCommentContent()).isEqualTo(content);
        assertThat(review.getValue().getCommentImages()).isEqualTo(images);
        assertThat(review.getValue().getStar()).isEqualTo(5);
    }

    @Test
    void validFollowUpWithNoImagesKeepsTheExistingProtocol() {
        configureOwnedOrder(OrderCommentStatusEnum.EVALUATED.getStatus());
        service.postReComment("INPUT00001", "ORDER-INPUT", "合成追评", "");
        ArgumentCaptor<OrderComment> review = ArgumentCaptor.forClass(OrderComment.class);
        verify(comments).updateByOrderId(review.capture(), eq("ORDER-INPUT"));
        assertThat(review.getValue().getRecommentContent()).isEqualTo("合成追评");
    }

    private void configureOwnedOrder(int commentStatus) {
        OrderInfo order = new OrderInfo();
        order.setUserId("INPUT00001");
        order.setOrderStatus(OrderStatusEnum.COMPLETED.getStatus());
        order.setCommentStatus(commentStatus);
        when(orders.selectByOrderId("ORDER-INPUT")).thenReturn(order);
        when(orders.updateByParam(any(), any())).thenReturn(1);
    }
}
