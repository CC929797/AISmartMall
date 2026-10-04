package com.easymall.controller;

import com.easymall.entity.dto.OrderStatusDTO;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderInfo;
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
}
