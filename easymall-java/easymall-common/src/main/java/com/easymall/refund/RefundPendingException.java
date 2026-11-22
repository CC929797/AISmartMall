package com.easymall.refund;

import com.easymall.exception.BusinessException;

/**
 * 发货或确认收货遇到未决退款时，后台保留原任务稍后处理。
 */
public class RefundPendingException extends BusinessException {
    public RefundPendingException() {
        super("订单退款结果待确认，请稍后操作");
    }
}
