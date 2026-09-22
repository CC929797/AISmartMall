package com.easymall.controller;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.OrderInfoService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单
 */
@RestController
@RequestMapping("/order")
public class OrderController extends ABaseController{
    @Resource
    private OrderInfoService orderInfoService;

    @RequestMapping("/postOrder")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postOrder(@RequestBody @Valid PostOrderDTO postOrderDTO) {

        PayInfoDTO payInfoDTO = orderInfoService.postOrder(getTokenUserInfo().getUserId(),postOrderDTO);
        return getSuccessResponseVO(payInfoDTO);
    }
}
