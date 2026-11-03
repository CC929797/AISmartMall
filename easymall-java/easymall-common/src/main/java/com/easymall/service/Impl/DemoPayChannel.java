package com.easymall.service.Impl;

import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.exception.BusinessException;
import com.easymall.service.PayChannel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Map;

/** 仅 demo Profile 使用的无外部调用适配器，演示页面不会生成真实扣款或成功通知。 */
@Profile("demo")
@Service("payChannel4Alipay")
public class DemoPayChannel implements PayChannel {
    @Override public PayInfoDTO getPayUrl(PayChannelEnum channel, String orderId, String subject, BigDecimal amount) {
        return new PayInfoDTO("<p>演示模式：未调用支付渠道，不会扣款。此订单保持待支付状态。</p>", orderId, amount);
    }
    @Override public PayOrderNotifyDTO payNotify(Map<String, String> params, String body) { throw new BusinessException("演示模式不接收支付回调"); }
    @Override public PayOrderNotifyDTO queryOrder(String orderId) { return null; }
    @Override public void refund(String sourceId, String refundId, BigDecimal amount) { throw new BusinessException("演示模式不执行退款"); }
    @Override public void closeOrder(String orderId) { }
}
