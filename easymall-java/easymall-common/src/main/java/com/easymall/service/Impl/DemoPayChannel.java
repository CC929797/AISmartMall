package com.easymall.service.Impl;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.exception.BusinessException;
import com.easymall.service.PayChannel;
import com.easymall.refund.RefundChannelResult;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 仅 demo Profile 使用的无外部调用适配器，演示页面不会生成真实扣款或成功通知。 */
@Profile("demo")
@Service("payChannel4Alipay")
public class DemoPayChannel implements PayChannel {
    @Override public PayInfoDTO getPayUrl(PayChannelEnum channel, String orderId, String subject, BigDecimal amount, Instant expiresAt) {
        PayInfoDTO page = new PayInfoDTO("<p>演示模式：未调用支付渠道，不会扣款。此订单保持待支付状态。</p>", orderId, amount);
        page.setPaymentStatus("DEMO");
        return page;
    }
    @Override public PayOrderNotifyDTO payNotify(Map<String, String> params, String body) { throw new BusinessException("演示模式不接收支付回调"); }
    @Override public PaymentQueryResult queryPayment(String orderId) {
        return PaymentQueryResult.of(PaymentQueryResult.Status.NOT_FOUND);
    }
    @Override public RefundChannelResult refund(String sourceId, String refundId, BigDecimal amount) { return RefundChannelResult.REJECTED; }
    @Override public RefundChannelResult queryRefund(String sourceId, String refundId, BigDecimal amount) { return RefundChannelResult.REJECTED; }
    /** 演示环境不会产生可扣款交易，也不冒充渠道已关闭历史交易。 */
    @Override
    public PaymentCloseResult closeOrder(String orderId) {
        return PaymentCloseResult.UNKNOWN;
    }
}
