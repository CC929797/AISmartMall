package com.easymall.task;

import com.easymall.entity.config.AppConfig;
import com.easymall.service.OrderInfoService;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 付款状态和关单仍从订单表恢复。模拟物流和自动收货由可靠任务 Worker 处理。 */
@Component
public class PayOrderTask {
    @Resource
    private OrderInfoService orderInfoService;
    @Resource
    private AppConfig appConfig;

    /** 记住本进程上一批查单的位置，下一轮继续向后扫描待付款的支付组。 */
    private String paymentCursor = "";

    /** 主动查单是回调的补充，每轮处理有限个不同的支付组。 */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000, scheduler = "paymentScheduler")
    public void checkPayOrder() {
        if (Boolean.TRUE.equals(appConfig.getAutoCheckPay())) {
            paymentCursor = orderInfoService.pollUnpaidPayments(paymentCursor);
        }
    }

    /** 统一推进超时关单和待确认取消，重启后继续从数据库恢复处理。 */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000, scheduler = "paymentScheduler")
    public void reconcileClosures() {
        orderInfoService.reconcilePaymentClosures();
    }
}
