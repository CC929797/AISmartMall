package com.easymall.service;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.PayChannelEnum;

import java.math.BigDecimal;
import java.util.Map;

public interface PayChannel {
    /**
     * 获取支付信息
     * @param payChannelEnum 支付渠道枚举
     * @param payOrderId 订单支付Id
     * @param subject 标题
     * @param amount 金额
     */
    PayInfoDTO getPayUrl(PayChannelEnum payChannelEnum, String payOrderId, String subject, BigDecimal amount);

    /**
     * 异步通知
     * @param requestParams 请求参数
     * @param jsonBody json对象
     */
    PayOrderNotifyDTO payNotify(Map<String,String> requestParams,String jsonBody);

    /**
     * 查询
     * @param payOrderId
     * @return
     */
    PayOrderNotifyDTO queryOrder(String payOrderId);

    /**
     * 退款
     * @param sourcePayOrderId 要退款的订单支付Id
     * @param payOrderId 退款时生成的Id
     * @param refundAmount 退款金额
     */
    void refund(String sourcePayOrderId, String payOrderId,BigDecimal refundAmount);

    /**
     * 关闭订单
     * @param payOrderId
     */
    void closeOrder(String payOrderId);
}
