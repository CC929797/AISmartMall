package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.enums.OrderCommentStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderItem;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.vo.OrderCountVO;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.refund.RefundService;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单
 */
@RestController
@RequestMapping("/order")
public class OrderController extends ABaseController{
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private OrderLogisticsInfoService orderLogisticsInfoService;
    @Resource
    private RefundService refundService;

    /**]
     * 提交订单
     * @param postOrderDTO 订单信息
     */
    @RequestMapping("/postOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postOrder(@RequestBody @Valid PostOrderDTO postOrderDTO) {

        PayInfoDTO payInfoDTO = orderInfoService.postOrder(getTokenUserInfo().getUserId(),postOrderDTO);
        return getSuccessResponseVO(payInfoDTO);
    }

    /** 网络重试前查询本人请求，PROCESSING不会被误当作已创建或已支付。 */
    @RequestMapping("/getOrderRequest")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getOrderRequest(@NotEmpty String requestId) {
        return getSuccessResponseVO(orderInfoService.getOrderRequest(getTokenUserInfo().getUserId(), requestId));
    }

    /**
     * 根据支付订单号获取订单信息
     * @param payOrderId
     * @return
     */
    @RequestMapping("/getOrderInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getOrderInfo(@NotEmpty String payOrderId) {
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        orderInfoQuery.setPayOrderId(payOrderId);
        orderInfoQuery.setUserId(getTokenUserInfo().getUserId());
        List<OrderInfo> orderInfoList = orderInfoService.findListByParam(orderInfoQuery);
        if (orderInfoList.isEmpty()){
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        OrderInfo orderInfo = orderInfoList.get(0);
        if (orderInfo == null || !orderInfo.getUserId().equals(getTokenUserInfo().getUserId())){
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        return getSuccessResponseVO(orderInfo);
    }

    /**
     * 查询我的订单
     * @param pageNo
     * @return
     */
    @RequestMapping("/loadMyOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadMyOrder(Integer pageNo,Integer status) {
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        buildStatus(orderInfoQuery,status);

        orderInfoQuery.setPageNo(pageNo);
        String userId = getTokenUserInfo().getUserId();
        orderInfoQuery.setUserId(userId);
        orderInfoQuery.setOrderBy("o.order_time desc");
        orderInfoQuery.setQueryItems(true);
        orderInfoQuery.setExecuteOrderStatusList(new Integer[]{OrderStatusEnum.DELETE.getStatus()});

        PaginationResultVO<OrderInfo> resultVO = orderInfoService.findListByPage(orderInfoQuery);
        List<OrderItem> items = new ArrayList<>();
        for (OrderInfo order : resultVO.getList()) {
            if (order.getOrderItemList() != null) items.addAll(order.getOrderItemList());
        }
        refundService.fillStatuses(userId, items);
        return getSuccessResponseVO(resultVO);
    }
    
    private void buildStatus(OrderInfoQuery query,Integer status) {
        if (status == null) {
            return;
        }
        OrderStatusEnum orderStatusEnum = OrderStatusEnum.getByStatus(status);
        if (orderStatusEnum == null || orderStatusEnum == OrderStatusEnum.DELETE) {
            return;
        }
        query.setFulfillmentOrderStatus(orderStatusEnum);

        query.setCommentStatus(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());
    }

    /**
     * 根据订单号获取支付信息(进行在订单页面中点击付款)
     * @param orderId 主订单Id
     * @return 支付信息
     */
    @RequestMapping("/getPayInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getPayInfo(@NotEmpty String orderId) {
        PayInfoDTO payInfoDTO = orderInfoService.getPayInfo(getTokenUserInfo().getUserId(), orderId);
        return getSuccessResponseVO(payInfoDTO);
    }

    /**
     * 删除订单
     * @param orderId
     * @return
     */
    @RequestMapping("/deleteOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO deleteOrder(@NotEmpty String orderId) {
        orderInfoService.deleteOrder(getTokenUserInfo().getUserId(),orderId);
        return getSuccessResponseVO(null);

    }

    /**
     * 取消订单
     * @param orderId
     * @return
     */
    @RequestMapping("/cancelOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO cancelOrder(@NotEmpty String orderId) {
        OrderStatusEnum result = orderInfoService.cancelOrder(getTokenUserInfo().getUserId(), orderId, OrderStatusEnum.CANCELLED);
        // 前端根据业务结果展示已取消、已付款或关单待确认。
        return getSuccessResponseVO(java.util.Map.of("orderStatus", result.getStatus()));
    }

    /**
     * 确认订单
     * @param orderId
     * @return
     */
    @RequestMapping("/confirmOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO confirmOrder(@NotEmpty String orderId) {
        orderInfoService.confirmOrder(getTokenUserInfo().getUserId(),orderId);
        return getSuccessResponseVO(null);
    }

    /**
     * 获取订单物流信息
     * @param orderId
     * @return
     */
    @RequestMapping("/getLogistics")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getLogistics(@NotEmpty String orderId) {
        return getSuccessResponseVO(orderLogisticsInfoService.getOrderLogisticsRecords(getTokenUserInfo().getUserId(),orderId));
    }

    /**
     * 退款
     * @param orderItemId
     * @return
     */
    @PostMapping("/refundOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO refundOrder(@NotEmpty String orderItemId, String requestId) {
        String userId = getTokenUserInfo().getUserId();
        if (requestId == null) {
            orderInfoService.refundByOrderItemId(userId, orderItemId);
            return getSuccessResponseVO(null);
        }
        return getSuccessResponseVO(refundService.apply(userId, orderItemId, requestId));
    }

    /**
     * 获取退款状态
     * @param orderItemId 子订单Id
     * @return 退款状态
     */
    @PostMapping("/getRefundStatus")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getRefundStatus(@NotEmpty String orderItemId) {
        return getSuccessResponseVO(refundService.status(getTokenUserInfo().getUserId(), orderItemId));
    }

    /**
     * 查询我的订单中待付款、待发货、待收货、待评价的订单数量
     */
    @RequestMapping("/getOrderCountInfo")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getOrderCountInfo(){
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        orderInfoQuery.setUserId(getTokenUserInfo().getUserId());
        orderInfoQuery.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
        Integer count = orderInfoService.findCountByParam(orderInfoQuery);

        List<OrderCountVO> orderCountVOList = new ArrayList<>();

        //待付款
        OrderCountVO orderCountVO = new OrderCountVO("pendingPayment", count);
        orderCountVOList.add(orderCountVO);

        //待发货
        orderInfoQuery.setFulfillmentOrderStatus(OrderStatusEnum.PAID);
        count = orderInfoService.findCountByParam(orderInfoQuery);
        orderCountVO = new OrderCountVO("pendingShipment", count);
        orderCountVOList.add(orderCountVO);

        //待收货
        orderInfoQuery.setFulfillmentOrderStatus(OrderStatusEnum.SHIPPED);
        count = orderInfoService.findCountByParam(orderInfoQuery);
        orderCountVO = new OrderCountVO("pendingReceipt", count);
        orderCountVOList.add(orderCountVO);

        //待评价
        orderInfoQuery.setOrderStatus(OrderStatusEnum.COMPLETED.getStatus());
        orderInfoQuery.setCommentStatus(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());
        count = orderInfoService.findCountByParam(orderInfoQuery);
        orderCountVO = new OrderCountVO("pendingComment", count);
        orderCountVOList.add(orderCountVO);

        return getSuccessResponseVO(orderCountVOList);
    }
}
