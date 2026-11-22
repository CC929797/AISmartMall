package com.easymall.service;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.order.PaymentCloseResult;
import com.easymall.order.PaymentQueryResult;
import com.easymall.security.PaymentGatewayPolicy;
import com.easymall.service.Impl.PayChannel4Alipay;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 仅用于测试验证：SDK客户端为替身，检查支付请求和查单结果合同，不访问支付宝网关。 */
class AlipayGatewayContractTest {
    private final PayChannel4Alipay adapter = new PayChannel4Alipay();
    private final AppConfig app = mock(AppConfig.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(adapter, "appConfig", app);
        ReflectionTestUtils.setField(adapter, "paymentGatewayPolicy", mock(PaymentGatewayPolicy.class));
        when(app.getProjectFolder()).thenReturn("target/synthetic/");
        when(app.getOrderExpireMinute()).thenReturn(5);
        when(app.getProjectDomain()).thenReturn("http://xxx.xxxx.com");
    }
    @Test void placeholderProjectDomainIsNotSentAsCallback() throws Exception {
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse(); response.setCode("10000"); response.setBody("<form>synthetic</form>");
        try (var clients = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response))) {
            assertThat(adapter.getPayUrl(PayChannelEnum.ALIPAY_PC, "synthetic-payment", "test", new BigDecimal("12.34"), java.time.Instant.parse("2026-11-15T12:30:00Z")).getPayInfo()).contains("synthetic");
            var request = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
            verify(clients.constructed().getFirst()).pageExecute(request.capture());
            assertThat(request.getValue().getNotifyUrl()).isNullOrEmpty();
            assertThat(((com.alipay.api.domain.AlipayTradePagePayModel) request.getValue().getBizModel()).getTimeExpire())
                    .isEqualTo("2026-11-15 20:30:00");
        }
    }
    @Test void configuredCallbackIsSentUnchanged() throws Exception {
        when(app.getAlipayNotifyUrl()).thenReturn("https://merchant.example/api/notify/alipayNotify");
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse(); response.setCode("10000"); response.setBody("synthetic");
        try (var clients = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response))) {
            adapter.getPayUrl(PayChannelEnum.ALIPAY_PC, "synthetic-payment", "test", new BigDecimal("12.34"), java.time.Instant.parse("2026-11-15T12:30:00Z"));
            var request = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
            verify(clients.constructed().getFirst()).pageExecute(request.capture());
            assertThat(request.getValue().getNotifyUrl()).isEqualTo("https://merchant.example/api/notify/alipayNotify");
        }
    }
    @Test void providerSystemErrorNeverConfirmsPayment() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("20000"); response.setSubCode("aop.ACQ.SYSTEM_ERROR");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            assertThat(adapter.queryPayment("synthetic-payment").payment()).isNull();
        }
    }
    @Test void missingTradeRemainsUnconfirmed() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("40004"); response.setSubCode("ACQ.TRADE_NOT_EXIST");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            assertThat(adapter.queryPayment("synthetic-payment").status()).isEqualTo(PaymentQueryResult.Status.NOT_FOUND);
        }
    }
    @Test void paidQueryCarriesProviderAmountForDatabaseComparison() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("10000"); response.setTradeStatus("TRADE_SUCCESS");
        response.setOutTradeNo("synthetic-payment"); response.setTradeNo("synthetic-channel"); response.setTotalAmount("12.34");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            var result = adapter.queryPayment("synthetic-payment").payment();
            assertThat(result.getAmount()).isEqualByComparingTo("12.34");
            assertThat(result.getChannelOrderId()).isEqualTo("synthetic-channel");
        }
    }

    @Test void signedGatewayResponseForDifferentMerchantOrderDoesNotConfirmPayment() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setOutTradeNo("other-payment");
        response.setTradeStatus("TRADE_SUCCESS");
        response.setTradeNo("synthetic-channel");
        response.setTotalAmount("12.34");
        try (var ignored = mockConstruction(DefaultAlipayClient.class,
                (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            assertThat(adapter.queryPayment("synthetic-payment").status())
                    .isEqualTo(com.easymall.order.PaymentQueryResult.Status.UNKNOWN);
        }
    }

    @Test void closeIdentifiesMissingTradesAndRejectsInconsistentGatewayResponses() throws Exception {
        AlipayTradeCloseResponse response = new AlipayTradeCloseResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.TRADE_NOT_EXIST");
        try (var ignored = mockConstruction(DefaultAlipayClient.class,
                (client, context) -> when(client.certificateExecute(any(AlipayTradeCloseRequest.class))).thenReturn(response))) {
            assertThat(adapter.closeOrder("synthetic-payment")).isEqualTo(PaymentCloseResult.NOT_FOUND);
            response.setOutTradeNo("other-payment");
            assertThat(adapter.closeOrder("synthetic-payment")).isEqualTo(PaymentCloseResult.UNKNOWN);
            response.setOutTradeNo("synthetic-payment");
            response.setCode("20000");
            assertThat(adapter.closeOrder("synthetic-payment")).isEqualTo(PaymentCloseResult.UNKNOWN);
        }
    }
}
