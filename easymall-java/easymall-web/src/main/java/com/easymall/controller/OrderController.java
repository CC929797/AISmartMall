package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.OrderInfoService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
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

}
