package com.easymall.service;

import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.enums.PayChannelEnum;
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
            assertThat(adapter.getPayUrl(PayChannelEnum.ALIPAY_PC, "synthetic-payment", "test", new BigDecimal("12.34")).getPayInfo()).contains("synthetic");
            var request = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
            verify(clients.constructed().getFirst()).pageExecute(request.capture());
            assertThat(request.getValue().getNotifyUrl()).isNullOrEmpty();
        }
    }
    @Test void configuredCallbackIsSentUnchanged() throws Exception {
        when(app.getAlipayNotifyUrl()).thenReturn("https://merchant.example/api/notify/alipayNotify");
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse(); response.setCode("10000"); response.setBody("synthetic");
        try (var clients = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response))) {
            adapter.getPayUrl(PayChannelEnum.ALIPAY_PC, "synthetic-payment", "test", new BigDecimal("12.34"));
            var request = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
            verify(clients.constructed().getFirst()).pageExecute(request.capture());
            assertThat(request.getValue().getNotifyUrl()).isEqualTo("https://merchant.example/api/notify/alipayNotify");
        }
    }
    @Test void providerSystemErrorNeverConfirmsPayment() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("20000"); response.setSubCode("aop.ACQ.SYSTEM_ERROR");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            assertThat(adapter.queryOrder("synthetic-payment")).isNull();
        }
    }
    @Test void missingTradeRemainsUnconfirmed() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("40004"); response.setSubCode("ACQ.TRADE_NOT_EXIST");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            assertThat(adapter.queryOrder("synthetic-payment")).isNull();
        }
    }
    @Test void paidQueryCarriesProviderAmountForDatabaseComparison() throws Exception {
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("10000"); response.setTradeStatus("TRADE_SUCCESS");
        response.setTradeNo("synthetic-channel"); response.setTotalAmount("12.34");
        try (var ignored = mockConstruction(DefaultAlipayClient.class, (client, context) -> when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response))) {
            var result = adapter.queryOrder("synthetic-payment");
            assertThat(result.getAmount()).isEqualByComparingTo("12.34");
            assertThat(result.getChannelOrderId()).isEqualTo("synthetic-channel");
        }
    }
}
