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
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import com.easymall.service.PayChannel;
import com.easymall.utils.StringTools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
    @Resource
    private OrderLogisticsInfoService orderLogisticsInfoService;


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

    /**
     * 自动发货
     */
    @PostConstruct
    public void consumeDeliveryOrder() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> queueDeliveryList = redisComponent.getTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_DELIVERY);
                    if(queueDeliveryList == null || queueDeliveryList.isEmpty()) {
                        Thread.sleep(1000);
                        continue;
                    }

                    for (String payOrderId : queueDeliveryList) {
                        if (redisComponent.removeTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_DELIVERY,payOrderId) > 0){
                            //通过支付订单Id去查询订单列表
                            OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
                            orderInfoQuery.setPayOrderId(payOrderId);
                            List<OrderInfo> orderInfoList = orderInfoService.findListByParam(orderInfoQuery);
                            //遍历订单列表，获取到订单Id并对每个订单进行发货
                            for (OrderInfo orderInfo : orderInfoList) {
                                OrderLogisticsInfo orderLogisticsInfo = new OrderLogisticsInfo();
                                orderLogisticsInfo.setLogisticsCompany("顺丰");
                                orderLogisticsInfo.setLogisticsNo("SF" + StringTools.getRandomNumber(Constants.LENGTH_10));
                                orderLogisticsInfo.setOrderId(orderInfo.getOrderId());
                                //发货
                                orderLogisticsInfoService.delivery(orderLogisticsInfo);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("自动发货任务出错！错误信息：" + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (Exception ex) {
                        log.error("休眠失败" + ex.getMessage());
                    }
                }
            }
        });
    }

    /**
     *  模拟物流
     */
    @PostConstruct
    public void consumeLogistics() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> orderList = redisComponent.getTimeOutOrder4Logistics();
                    if(orderList == null || orderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }
                    for (String orderId : orderList) {
                        if (redisComponent.removeTimeOutOrder4Logistics(orderId) > 0){
                            orderLogisticsInfoService.mockOrderLogistics(orderId);
                        }
                    }
                } catch (Exception e) {
                    log.error("模拟物流信息失败，错误信息：" + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (Exception ex) {
                        log.error("休眠失败" + ex.getMessage());
                    }
                }
            }
        });
    }

    /**
     * 自动确认订单
     */
    @PostConstruct
    public void consumeConfirmOrder() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> orderList = redisComponent.getTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM);
                    if(orderList == null || orderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }
                    for (String orderId : orderList) {
                        if (redisComponent.removeTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM,orderId) > 0){
                            //确认订单
                            orderInfoService.confirmOrder(null,orderId);
                        }
                    }
                }catch (Exception e){
                    log.error("自动确认收费失败!,错误信息：" + e.getMessage());
                    try{
                        Thread.sleep(5000);
                    }catch (Exception ex) {
                        log.error("休眠失败" + ex.getMessage());
                    }
                }
            }
        });
    }
}
