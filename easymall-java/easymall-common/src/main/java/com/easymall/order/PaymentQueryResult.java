package com.easymall.order;

import com.easymall.entity.dto.PayOrderNotifyDTO;

/** 支付渠道查单结果。区分渠道未知、未创建和已关闭未付款，供关单流程决定是否释放库存。 */
public record PaymentQueryResult(Status status, PayOrderNotifyDTO payment) {
    public enum Status {
        /**
         * PAID: 已付款
         * WAITING: 未创建
         * CLOSED_UNPAID: 已关闭未付款
         * NOT_FOUND: 未找到
         * UNKNOWN: 未知
         */
        PAID, WAITING, CLOSED_UNPAID, NOT_FOUND, UNKNOWN
    }

    /** 只有已付款结果携带经过适配器校验的付款凭证。 */
    public static PaymentQueryResult paid(PayOrderNotifyDTO payment) {
        return new PaymentQueryResult(Status.PAID, payment);
    }

    public static PaymentQueryResult of(Status status) {
        return new PaymentQueryResult(status, null);
    }
}
