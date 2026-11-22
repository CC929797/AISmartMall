package com.easymall.files;

import com.easymall.exception.BusinessException;
import com.easymall.security.PaymentGatewayPolicy;
import com.easymall.service.Impl.DemoPayChannel;
import com.easymall.entity.enums.PayChannelEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaymentGatewayPolicyTest {
    @ParameterizedTest @ValueSource(strings = {"https://openapi-sandbox.dl.alipaydev.com/gateway.do", "https://openapi.alipaydev.com/gateway.do"})
    void sandboxAcceptsOnlyKnownHttpsGateways(String url) { new PaymentGatewayPolicy().validate(url); }
    @ParameterizedTest @ValueSource(strings = {"https://openapi.alipay.com/gateway.do", "http://openapi.alipaydev.com/gateway.do",
            "https://attacker.example/gateway.do", "https://openapi.alipaydev.com.attacker.example/gateway.do",
            "https://user:pass@openapi.alipaydev.com/gateway.do", "https://openapi.alipaydev.com/gateway.do?key=fixture",
            "https://openapi.alipaydev.com:444/gateway.do", "https://openapi.alipaydev.com/other", "not-a-url"})
    void wrongModeAndArbitraryEndpointsAreRejected(String url) { assertThrows(BusinessException.class, () -> new PaymentGatewayPolicy().validate(url)); }
    @Test void liveNeedsExplicitModeAndProductionGateway() {
        var policy = new PaymentGatewayPolicy();
        ReflectionTestUtils.setField(policy, "mode", "live");
        policy.validate("https://openapi.alipay.com/gateway.do");
        assertThrows(BusinessException.class, () -> policy.validate("https://openapi.alipaydev.com/gateway.do"));
    }
    @Test void unknownModeFailsClosed() {
        var policy = new PaymentGatewayPolicy();
        ReflectionTestUtils.setField(policy, "mode", "typo");
        assertThrows(BusinessException.class, () -> policy.validate("https://openapi.alipaydev.com/gateway.do"));
    }
    @Test void demoNeverPretendsToHaveReceivedOrRefundedMoney() {
        var demo = new DemoPayChannel();
        var page = demo.getPayUrl(PayChannelEnum.ALIPAY_PC, "fixture", "<script>fixture</script>", BigDecimal.ONE, java.time.Instant.now());
        assertFalse(page.getPayInfo().contains("<script>"));
        assertTrue(page.getPayInfo().contains("不会扣款"));
        assertEquals(com.easymall.order.PaymentQueryResult.Status.NOT_FOUND, demo.queryPayment("fixture").status());
        assertThrows(BusinessException.class, () -> demo.payNotify(Map.of(), "fixture"));
        assertEquals(com.easymall.refund.RefundChannelResult.REJECTED, demo.refund("fixture", "fixture", BigDecimal.ONE));
    }
}
