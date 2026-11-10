package com.easymall.controller;

import com.easymall.controller.notify.AlipayNotifyController;
import com.easymall.entity.dto.PayOrderNotifyDTO;
import com.easymall.exception.BusinessException;
import com.easymall.security.*;
import com.easymall.service.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 仅用于测试验证：验证匿名渠道通知精确放行、失败应答和重复参数，不调用真实支付宝。 */
class AlipayNotifyControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final PayChannel alipay = mock(PayChannel.class);
    private final OrderInfoService orders = mock(OrderInfoService.class);
    private final UserAuthenticationService authentication = mock(UserAuthenticationService.class);

    @BeforeEach void open() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(f -> {
            f.registerSingleton("payChannel4Alipay", alipay); f.registerSingleton("orderInfoService", orders);
            f.registerSingleton("userAuthenticationService", authentication);
        });
        context.register(Fixture.class); context.refresh(); mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void close() { context.close(); }
    @Test void providerCallbackWorksWithoutUserLogin() throws Exception {
        var payment = new PayOrderNotifyDTO("synthetic-order", "synthetic-trade");
        when(alipay.payNotify(anyMap(), isNull())).thenReturn(payment);
        mvc.perform(post("/notify/alipayNotify").param("sign", "synthetic")).andExpect(content().string("success"));
        verify(orders).payOrderSuccess(payment); verifyNoInteractions(authentication);
    }
    @Test void invalidSignatureNeverConfirmsOrder() throws Exception {
        when(alipay.payNotify(anyMap(), isNull())).thenThrow(new BusinessException("synthetic invalid signature"));
        mvc.perform(post("/notify/alipayNotify").param("sign", "synthetic")).andExpect(content().string("failure"));
        verifyNoInteractions(orders);
    }
    @Test void duplicateParametersAreRejectedBeforeVerification() throws Exception {
        mvc.perform(post("/notify/alipayNotify").param("sign", "one", "two")).andExpect(content().string("failure"));
        verifyNoInteractions(alipay, orders);
    }
    @Test void callbackPrefixDoesNotExposeOtherEndpoints() throws Exception {
        mvc.perform(post("/notify/alipayNotify/future")).andExpect(jsonPath("$.code").value(901));
        verifyNoInteractions(alipay, orders);
    }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc
    @Import({WebAuthenticationConfiguration.class, AlipayNotifyController.class, AGlobalExceptionHandlerController.class, Probe.class})
    static class Fixture { }
    @RestController static class Probe {
        @PostMapping("/notify/alipayNotify/future") public String other() { return "private"; }
    }
}
