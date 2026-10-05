package com.easymall.controller;

import com.easymall.entity.dto.OrderStatusDTO;
import com.easymall.entity.enums.CommentStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderComment;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.*;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/order")
public class OrderController extends ABaseController{
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private OrderLogisticsInfoService orderLogisticsInfoService;
    @Resource
    private OrderCommentService orderCommentService;

    /**
     * 获取到订单状态列表
     * @return
     */
    @RequestMapping("/loadOrderStatus")
    public ResponseVO loadOrderStatus(){
        return getSuccessResponseVO(Arrays.stream(OrderStatusEnum.values()).map(OrderStatusDTO::from).collect(Collectors.toList()));
    }

    /**
     * 管理端查看所有订单
     * @param orderInfoQuery
     * @return
     */
    @RequestMapping("/loadOrder")
    public ResponseVO loadOrder(OrderInfoQuery orderInfoQuery){
        orderInfoQuery.setOrderBy("o.order_time desc");
        orderInfoQuery.setQueryItems(true);
        orderInfoQuery.setQueryUser(true);
        PaginationResultVO<OrderInfo> resultVO = this.orderInfoService.findListByPage(orderInfoQuery);

        return getSuccessResponseVO(resultVO);

    }

    /**
     * 管理员要发货时获取到默认的发货信息表
     * @param orderId 订单Id
     */
    @RequestMapping("/getLogistics")
    public ResponseVO getLogistics(String orderId){
        OrderLogisticsInfo orderLogisticsInfo = this.orderLogisticsInfoService.getOrderLogisticsInfoByOrderId(orderId);
        return getSuccessResponseVO(orderLogisticsInfo);
    }

    /**
     * 确认发货信息无误后，手动点击发货
     * @param orderLogisticsInfo
     * @return
     */
    @RequestMapping("/delivery")
    public ResponseVO delivery(OrderLogisticsInfo orderLogisticsInfo){
        orderLogisticsInfoService.delivery(orderLogisticsInfo);
        return getSuccessResponseVO(null);
    }

    /**
     * 商家回复之前，获取到订单之前的用户评价
     * @param orderId
     * @return
     */
    @RequestMapping("/getComment")
    public ResponseVO getComment(String orderId){
        OrderComment orderComment = orderCommentService.getOrderCommentByOrderId(orderId);
        return getSuccessResponseVO(orderComment);
    }

    /**
     * 商家回复
      * @param orderId 订单Id
     *  @param commentBizReply 商家回复内容
     */
    @RequestMapping("/bizComment")
    public ResponseVO bizComment(String orderId,String commentBizReply){
        orderCommentService.postBizComment(orderId,commentBizReply);
        return getSuccessResponseVO(null);
    }

    /**
     * 获取到所有评论
     * @param query
     * @return
     */
    @RequestMapping("/loadComment")
    public ResponseVO loadComment(OrderCommentQuery query){
        query.setOrderBy("o.comment_time desc");
        query.setQueryProduct(true);
        query.setQueryUserInfo(true);
        return getSuccessResponseVO(orderCommentService.findListByPage(query));
    }

    /**
     * 管理员删除评论
     * @param orderId
     * @return
     */
    @RequestMapping("/delComment")
    public ResponseVO delComment(String orderId){
        OrderComment orderComment = new OrderComment();
        orderComment.setStatus(CommentStatusEnum.DEL.getStatus());

        OrderCommentQuery query = new OrderCommentQuery();
        query.setOrderId(orderId);

        return getSuccessResponseVO(orderCommentService.updateByParam(orderComment, query));
    }
}
