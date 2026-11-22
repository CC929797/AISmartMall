package com.easymall.refund;

import java.math.BigDecimal;

/**
 * 供用户查看的退款进度。
 * @param refundId 退款ID
 * @param orderItemId 订单项ID
 * @param amount 退款金额
 * @param status 退款状态
 */
public record RefundView(String refundId, String orderItemId, BigDecimal amount, RefundStatus status) { }
