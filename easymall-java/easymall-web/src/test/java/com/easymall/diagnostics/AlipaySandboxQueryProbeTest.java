package com.easymall.diagnostics;

import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 仅用于自动化测试：全部使用合成配置和模拟客户端，不请求真实支付宝。 */
class AlipaySandboxQueryProbeTest {
    @TempDir Path directory;

    @Test
    void checkOnlyLoadsCertificateConfigurationWithoutSendingRequest() throws Exception {
        Path config = configuration("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        AlipayClient client = mock(AlipayClient.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int result = AlipaySandboxQueryProbe.run(new String[]{"--check", "--config=" + config},
                new PrintStream(output, true, StandardCharsets.UTF_8), loaded -> {
                    assertEquals("9021000000000000", loaded.getAppId());
                    assertEquals("synthetic-private-key", loaded.getPrivateKey());
                    assertEquals(directory.resolve("app.crt").toString(), loaded.getAppCertPath());
                    assertEquals(directory.resolve("public.crt").toString(), loaded.getAlipayPublicCertPath());
                    assertEquals(directory.resolve("root.crt").toString(), loaded.getRootCertPath());
                    assertNull(loaded.getAlipayPublicKey());
                    assertEquals("RSA2", loaded.getSignType());
                    return client;
                });
        assertEquals(0, result);
        verifyNoInteractions(client);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("未发送请求"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("synthetic-private-key"));
    }

    @Test
    void queriesSpecifiedOrderOnceAndPrintsOnlyDiagnosticFields() throws Exception {
        Path config = configuration("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        AlipayClient client = mock(AlipayClient.class);
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("20000");
        response.setMsg("Service Currently Unavailable");
        response.setSubCode("aop.ACQ.SYSTEM_ERROR");
        response.setSubMsg("系统异常");
        response.setBody("synthetic-full-body-with-signature-and-buyer-information");
        when(client.certificateExecute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int result = AlipaySandboxQueryProbe.run(new String[]{"SYNTHETIC_ORDER_001", "--config=" + config},
                new PrintStream(output, true, StandardCharsets.UTF_8), loaded -> client);
        assertEquals(0, result);
        ArgumentCaptor<AlipayTradeQueryRequest> request = ArgumentCaptor.forClass(AlipayTradeQueryRequest.class);
        verify(client).certificateExecute(request.capture());
        verifyNoMoreInteractions(client);
        assertEquals("SYNTHETIC_ORDER_001", ((AlipayTradeQueryModel) request.getValue().getBizModel()).getOutTradeNo());
        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("code=20000"));
        assertTrue(printed.contains("sub_code=aop.ACQ.SYSTEM_ERROR"));
        assertTrue(printed.contains("sub_msg=系统异常"));
        assertTrue(printed.contains("trade_status=（未返回）"));
        assertFalse(printed.contains("synthetic-private-key"));
        assertFalse(printed.contains("synthetic-full-body"));
    }

    @Test
    void productionGatewayIsRejectedBeforeClientCreation() throws Exception {
        Path config = configuration("https://openapi.alipay.com/gateway.do");
        int result = AlipaySandboxQueryProbe.run(new String[]{"--config=" + config},
                new PrintStream(new ByteArrayOutputStream()), loaded -> {
                    fail("正式网关不能进入客户端创建或发送请求阶段");
                    return null;
                });
        assertEquals(2, result);
    }

    @Test
    void unexpectedSdkExceptionDoesNotRevealItsMessage() throws Exception {
        Path config = configuration("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int result = AlipaySandboxQueryProbe.run(new String[]{"--check", "--config=" + config},
                new PrintStream(output, true, StandardCharsets.UTF_8), loaded -> {
                    throw new IllegalStateException("synthetic-secret-in-error-message");
                });
        assertEquals(2, result);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("IllegalStateException"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("synthetic-secret"));
    }

    private Path configuration(String gateway) throws Exception {
        for (String name : new String[]{"app.crt", "public.crt", "root.crt"}) {
            Files.writeString(directory.resolve(name), "synthetic-certificate-not-used-by-mocked-client");
        }
        Path config = directory.resolve("synthetic.yml");
        Files.writeString(config, """
                project:
                  folder: '%s'
                alipay:
                  appid: '9021000000000000'
                  appPrivateKey: ${alipay.probe.synthetic-key:synthetic-private-key}
                  appCertPath: app.crt
                  alipayPublicCertPath: public.crt
                  alipayRootCertPath: root.crt
                  serverUrl: '%s'
                """.formatted(directory.toString().replace('\\', '/'), gateway));
        return config;
    }
}
