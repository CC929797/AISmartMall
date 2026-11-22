package com.easymall.task;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.refund.RefundPendingException;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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


    private String paymentCursor = "";

    /**
     * 主动查单是回调的补充，每轮处理有限个不同的支付组。
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000, scheduler = "paymentScheduler")
    public void checkPayOrder() {
        if (Boolean.TRUE.equals(appConfig.getAutoCheckPay())) {
            paymentCursor = orderInfoService.pollUnpaidPayments(paymentCursor);
        }
    }

    /**
     * 定时任务
     * 统一推进超时关单和待确认取消。重启后继续从数据库恢复处理。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000, scheduler = "paymentScheduler")
    public void reconcileClosures() {
        orderInfoService.reconcilePaymentClosures();
    }

    /**
     *  模拟物流
     */
    @PostConstruct
    public void consumeLogistics() {
        if (!appConfig.isSimulatedLogistics()) {
            return;
        }
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> orderList = redisComponent.getTimeOutOrder4Logistics();
                    if (orderList == null || orderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }
                    for (String orderId : orderList) {
                        if (redisComponent.removeTimeOutOrder4Logistics(orderId) > 0) {
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
        if (!appConfig.isSimulatedLogistics()) {
            return;
        }
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> orderList = redisComponent.getTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM);
                    if (orderList == null || orderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }
                    for (String orderId : orderList) {
                        if (redisComponent.removeTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM,orderId) > 0) {
                            //确认订单
                            try {
                                orderInfoService.confirmOrder(null, orderId);
                            } catch (RefundPendingException pending) {
                                redisComponent.addOrder2DelayQueue(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM, 1, orderId);
                            }
                        }
                    }
                }catch (Exception e) {
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
