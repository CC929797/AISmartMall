package com.easymall.service;

import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.utils.JsonUtils;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * AI执行订单相关服务操作
 */
@Service
@Slf4j
public class OrderService {
    @Resource
    private OrderInfoService orderInfoService;

    @Resource
    private OrderLogisticsInfoService orderLogisticsInfoService;

    @Resource
    private OrderCommentService orderCommentService;

    /**
     * AI退款
     * @param userId 用户Id
     * @param orderId 订单Id
     * @return 退款结果
     */
    @Tool(name = "refund",description = "退款")
    public String refund(@ToolParam(description = "用户Id") String userId,@ToolParam(description = "订单Id") String orderId) {

        if (StringTools.isEmpty(orderId)) {
            return "请提供完整的订单号";
        }
        try {
            orderInfoService.refundByOrderItemId(userId, orderId);
        }catch (BusinessException e){
            return e.getMessage();
        }catch (Exception e){
            return ResponseCodeEnum.CODE_500.getMsg();
        }

        return "退款成功";
    }

    /**
     * AI确认收货
     * @param userId 用户Id
     * @param orderId 订单Id
     * @return 确认收货结果
     */
    @Tool(name = "orderConfirm",description = "订单确认收货")
    public String orderConfirm(@ToolParam(description = "用户Id") String userId,@ToolParam(description = "订单Id") String orderId) {

        if (StringTools.isEmpty(orderId)) {
            return "请提供完整的订单号";
        }
        try {
            orderInfoService.confirmOrder(userId, orderId);
        }catch (BusinessException e){
            return e.getMessage();
        }catch (Exception e){
            return ResponseCodeEnum.CODE_500.getMsg();
        }

        return "确认收货成功";
    }

    /**
     * AI取消订单
     * @param userId 用户Id
     * @param orderId 订单Id
     * @return 取消订单结果
     */
    @Tool(name = "cancelOrder",description = "取消订单")
    public String cancelOrder(@ToolParam(description = "用户Id") String userId,@ToolParam(description = "订单Id") String orderId) {

        if (StringTools.isEmpty(orderId)) {
            return "请提供完整的订单号";
        }
        try {
            orderInfoService.cancelOrder(userId, orderId, OrderStatusEnum.CANCELLED);
        }catch (BusinessException e){
            return e.getMessage();
        }catch (Exception e){
            return ResponseCodeEnum.CODE_500.getMsg();
        }

        return "取消订单成功";
    }

    /**
     * AI查询订单物流信息
     * @param userId 用户Id
     * @param orderId 订单Id
     * @return 订单物流信息
     */
    @Tool(name = "queryOrderLogistics",description = "查询订单物流信息")
    public String queryOrderLogistics(@ToolParam(description = "用户Id") String userId,@ToolParam(description = "订单Id") String orderId) {

        if (StringTools.isEmpty(orderId)) {
            return "请提供完整的订单号";
        }
        try {
            OrderLogisticsInfo orderLogisticsInfo = orderLogisticsInfoService.getOrderLogisticsRecords(userId, orderId);
            return JsonUtils.convertObj2Json(orderLogisticsInfo);
        }catch (BusinessException e){
            return e.getMessage();
        }catch (Exception e){
            return ResponseCodeEnum.CODE_500.getMsg();
        }
    }

    /**
     * AI商品评价
     * @param userId 用户Id
     * @param orderId 订单Id
     * @param commentContent 评论内容
     * @param star 评价星级1-5级
     * @return 评价结果
     */
    @Tool(name = "productReview",description = "商品评价")
    public String productReview(@ToolParam(description = "用户Id") String userId,
                                @ToolParam(description = "订单Id") String orderId,
                                @ToolParam(description = "评论内容") String commentContent,
                                @ToolParam(description = "评价星级1-5级") Integer star) {
        if (StringTools.isEmpty(orderId)) {
            return "请提供完整的订单号";
        }
        if (StringTools.isEmpty(commentContent)) {
            return "请输入评论内容";
        }
        try {
            orderCommentService.postComment(userId, orderId, commentContent, null, star);
            return "评价成功";
        }catch (BusinessException e){
            return e.getMessage();
        }catch (Exception e){
            return ResponseCodeEnum.CODE_500.getMsg();
        }
    }
}
