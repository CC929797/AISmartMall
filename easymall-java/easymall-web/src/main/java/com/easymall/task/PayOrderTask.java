package com.easymall.task;

import com.easymall.commonent.RedisComponent;
import com.easymall.commonent.SpringContext;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.service.OrderInfoService;
import com.easymall.service.PayChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class PayOrderTask {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private AppConfig appConfig;


    /**
     * 实际项目不会这么使用，都是通过支付宝回调来完成
     */
    @PostConstruct
    public void checkPayOrder() {
        if (!appConfig.getAutoCheckPay()){
            return;
        }
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
                    orderInfoQuery.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
                    List<OrderInfo> orderInfoList = orderInfoService.findListByParam(orderInfoQuery);
                    for (OrderInfo orderInfo : orderInfoList) {
                        PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(orderInfo.getPayScene());
                        PayChannel payChannel = (PayChannel) SpringContext.getBean(payChannelEnum.getBeanName());
                        PayOrderNotifyDTO payOrderNotifyDTO = payChannel.queryOrder(orderInfo.getPayOrderId());
                        if(payOrderNotifyDTO == null){
                            continue;
                        }
                        orderInfoService.payOrderSuccess(payOrderNotifyDTO);
                    }
                    Thread.sleep(10000);
                }catch (Exception e){
                    log.error("查询支付订单失败",e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        log.error("休眠失败",ex);
                    }

                }
            }
        });
    }


    /**
     * 自动取消订单
     */
    @PostConstruct
    public void consumeDelayOrder() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> queueOrderList = redisComponent.getTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE);
                    if(queueOrderList == null || queueOrderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }

                    for (String orderId : queueOrderList) {
                        if (redisComponent.removeTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE,orderId) > 0){
                            OrderInfo orderInfo = orderInfoService.getOrderInfoByOrderId(orderId);
                            if (!OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(orderInfo.getOrderStatus())) {
                                continue;
                            }
                            orderInfoService.cancelOrder(null,orderId,OrderStatusEnum.CLOSED);
                        }
                    }
                }catch (Exception e) {
                    log.error("支付订单任务出错！错误信息：" + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    }catch (Exception ex) {
                        log.error("休眠失败" + ex.getMessage());
                    }
                }
            }
        });
        log.info("定时任务开始启动");
    }
}
