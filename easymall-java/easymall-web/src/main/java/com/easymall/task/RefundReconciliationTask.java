package com.easymall.task;

import com.easymall.refund.RefundService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期查证已持久化的未决退款，数据库版本决定由哪个实例处理。 */
@Component
public class RefundReconciliationTask {
    private final RefundService refunds;

    public RefundReconciliationTask(RefundService refunds) {
        this.refunds = refunds;
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 15000, scheduler = "refundScheduler")
    public void reconcile() {
        refunds.reconcileDue();
    }
}
