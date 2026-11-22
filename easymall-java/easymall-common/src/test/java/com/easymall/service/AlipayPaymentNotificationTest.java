package com.easymall.service;

import com.alipay.api.internal.util.AlipaySignature;
import com.easymall.entity.config.AppConfig;
import com.easymall.exception.BusinessException;
import com.easymall.service.Impl.PayChannel4Alipay;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅用于测试验证：临时生成合成支付宝签名密钥/证书，不连接网关、不使用真实支付凭据。 */
@SuppressWarnings("deprecation")
class AlipayPaymentNotificationTest {
    private final PayChannel4Alipay channel = new PayChannel4Alipay();
    private KeyPair provider;

    @BeforeEach void prepare() throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048); provider = keys.generateKeyPair();
        var cert = new X509V3CertificateGenerator();
        cert.setSerialNumber(BigInteger.ONE); cert.setIssuerDN(new X500Principal("CN=SyntheticPaymentProvider"));
        cert.setSubjectDN(new X500Principal("CN=SyntheticPaymentProvider")); cert.setPublicKey(provider.getPublic());
        cert.setNotBefore(new Date(System.currentTimeMillis() - 60_000)); cert.setNotAfter(new Date(System.currentTimeMillis() + 3_600_000));
        cert.setSignatureAlgorithm("SHA256withRSA");
        Path directory = Path.of("target/payment-fixtures", UUID.randomUUID().toString()).toAbsolutePath(); Files.createDirectories(directory);
        // 合成证书保留在忽略的target下，不执行递归删除。
        Files.write(directory.resolve("provider.crt"), cert.generate(provider.getPrivate()).getEncoded());
        AppConfig app = mock(AppConfig.class);
        when(app.getAlipayAppid()).thenReturn("2026110900000000");
        when(app.getAlipaySellerId()).thenReturn("2088000000000001");
        when(app.getProjectFolder()).thenReturn(directory + "/");
        when(app.getAlipayPublicCertPath()).thenReturn("provider.crt");
        when(app.getAlipayAppCertPath()).thenReturn("missing-application-certificate.crt");
        ReflectionTestUtils.setField(channel, "appConfig", app);
    }

    private Map<String,String> signed(String app, String status, String amount) throws Exception {
        return signed(app, status, amount, "2088000000000001");
    }

    private Map<String,String> signed(String app, String status, String amount, String seller) throws Exception {
        Map<String,String> params = new HashMap<>(Map.of("app_id", app, "trade_status", status,
                "out_trade_no", "SYNTHETIC_ORDER_001", "trade_no", "SYNTHETIC_CHANNEL_001", "total_amount", amount,
                "seller_id", seller));
        String signature = AlipaySignature.rsaSign(AlipaySignature.getSignContent(params), Base64.getEncoder().encodeToString(provider.getPrivate().getEncoded()), "UTF-8", "RSA2");
        params.put("sign_type", "RSA2"); params.put("sign", signature); return params;
    }

    @Test void verifiedPaymentUsesProviderCertificateAndKeepsOriginalParameters() throws Exception {
        Map<String,String> params = Map.copyOf(signed("2026110900000000", "TRADE_SUCCESS", "12.34"));
        var result = channel.payNotify(params, null);
        assertThat(result.getPayOrderId()).isEqualTo("SYNTHETIC_ORDER_001");
        assertThat(result.getAmount()).isEqualByComparingTo("12.34");
        assertThat(params.get("sign_type")).isEqualTo("RSA2");
    }
    @Test void tamperedAmountIsRejected() throws Exception {
        var params = signed("2026110900000000", "TRADE_SUCCESS", "12.34"); params.put("total_amount", "0.01");
        assertThatThrownBy(() -> channel.payNotify(params, null)).isInstanceOf(BusinessException.class);
    }
    @Test void validSignatureForDifferentApplicationIsRejected() throws Exception {
        var params = signed("2026110900000001", "TRADE_SUCCESS", "12.34");
        assertThatThrownBy(() -> channel.payNotify(params, null)).isInstanceOf(BusinessException.class).hasMessageContaining("应用");
    }
    @Test void pendingTradeDoesNotConfirmPayment() throws Exception {
        assertThat(channel.payNotify(signed("2026110900000000", "WAIT_BUYER_PAY", "12.34"), null)).isNull();
    }

    @Test void validSignatureForDifferentSellerIsRejected() throws Exception {
        var params = signed("2026110900000000", "TRADE_SUCCESS", "12.34", "2088000000000002");
        assertThatThrownBy(() -> channel.payNotify(params, null)).isInstanceOf(BusinessException.class).hasMessageContaining("收款方");
    }
    @Test void finishedTradeStillConfirmsPayment() throws Exception {
        assertThat(channel.payNotify(signed("2026110900000000", "TRADE_FINISHED", "12.34"), null).getAmount()).isEqualByComparingTo("12.34");
    }
}
