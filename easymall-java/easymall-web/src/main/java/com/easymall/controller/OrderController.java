package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.enums.OrderCommentStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        orderInfoQuery.setUserId(getTokenUserInfo().getUserId());
        orderInfoQuery.setOrderBy("o.order_time desc");
        orderInfoQuery.setQueryItems(true);
        orderInfoQuery.setExecuteOrderStatusList(new Integer[]{OrderStatusEnum.DELETE.getStatus()});

        PaginationResultVO<OrderInfo> resultVO = orderInfoService.findListByPage(orderInfoQuery);
        return getSuccessResponseVO(resultVO);
    }
    
    private void buildStatus(OrderInfoQuery query,Integer status) {
        if (status == null) {
            return;
        }
        OrderStatusEnum orderStatusEnum = OrderStatusEnum.getByStatus(status);
        if (orderStatusEnum.DELETE == orderStatusEnum) {
            return;
        }
        query.setOrderStatus(orderStatusEnum.getStatus());
        /*if (OrderStatusEnum.COMPLETED != orderStatusEnum) {
            return;
        }*/

        query.setCommentStatus(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());
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
        orderInfoService.cancelOrder(getTokenUserInfo().getUserId(),orderId,OrderStatusEnum.CANCELLED);
        return getSuccessResponseVO(null);
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
    @RequestMapping("/refundOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO refundOrder(@NotEmpty String orderItemId) {
        orderInfoService.refundByOrderItemId(getTokenUserInfo().getUserId(),orderItemId);
        return getSuccessResponseVO(null);
    }
}
