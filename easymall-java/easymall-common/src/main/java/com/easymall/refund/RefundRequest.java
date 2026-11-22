package com.easymall.refund;

import java.math.BigDecimal;

/**
 * 持久化退款记录。一次退款的渠道请求号与金额在预留时固定。
 * @param refundId 退款Id
 * @param userId 用户Id
 * @param requestId 请求Id
 * @param orderId 订单Id
 * @param orderItemId 订单项Id
 * @param payOrderId 支付订单Id
 * @param payScene 支付场景
 * @param channelRequestNo 渠道请求号
 * @param amount 金额
 * @param status 退款状态
 * @param version 版本
 */
public record RefundRequest(String refundId, String userId, String requestId, String orderId,
                            String orderItemId, String payOrderId, String payScene,
                            String channelRequestNo, BigDecimal amount, RefundStatus status, long version) {
    public RefundView view() {
        return new RefundView(refundId, orderItemId, amount, status);
    }
}
