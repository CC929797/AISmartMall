package com.easymall.service;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.refund.RefundChannelResult;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public interface PayChannel {
    /**
     * 获取支付信息
     * @param payChannelEnum 支付渠道枚举
     * @param payOrderId 订单支付Id
     * @param subject 标题
     * @param amount 金额
     * @param expiresAt 商城为同一支付单持久化的固定付款截止时间
     */
    PayInfoDTO getPayUrl(PayChannelEnum payChannelEnum, String payOrderId, String subject, BigDecimal amount,
                        Instant expiresAt);

    /**
     * 异步通知
     * @param requestParams 请求参数
     * @param jsonBody json对象
     */
    PayOrderNotifyDTO payNotify(Map<String,String> requestParams,String jsonBody);

    /**
     * 按原支付单号查证付款情况，区分已付款、已关闭未付款和无法确认的结果。
     */
    PaymentQueryResult queryPayment(String payOrderId);

    /**
     * 退款
     * @param sourcePayOrderId 要退款的订单支付Id
     * @param payOrderId 退款时生成的Id
     * @param refundAmount 退款金额
     */
    RefundChannelResult refund(String sourcePayOrderId, String payOrderId, BigDecimal refundAmount);

    /**
     * 查询退款
      * @param sourcePayOrderId 原订单支付Id
     * @param refundRequestNo 退款请求Id
     * @param refundAmount 退款金额
     * @return 退款结果
     */
    RefundChannelResult queryRefund(String sourcePayOrderId, String refundRequestNo, BigDecimal refundAmount);

    /**
     * 按商城支付单号发起关单，区分明确关闭、查无交易和结果不明。
     */
    PaymentCloseResult closeOrder(String payOrderId);

    /** 渠道是否允许对表单已过期、经关单和查单双重确认不存在的交易做本地收尾。 */
    default boolean supportsClosingExpiredMissingTrades() {
        return false;
    }
}
