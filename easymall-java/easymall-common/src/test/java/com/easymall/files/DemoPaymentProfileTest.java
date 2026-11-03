package com.easymall.files;

import com.easymall.entity.config.AppConfig;
import com.easymall.security.PaymentGatewayPolicy;
import com.easymall.service.Impl.DemoPayChannel;
import com.easymall.service.Impl.PayChannel4Alipay;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoPaymentProfileTest {
    @Test void demoProfileSelectsOnlyNonNetworkAdapter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("demo");
            context.register(DemoPayChannel.class, PayChannel4Alipay.class);
            context.refresh();
            assertInstanceOf(DemoPayChannel.class, context.getBean("payChannel4Alipay"));
        }
    }
    @Test void otherProfilesSelectTheGuardedSdkAdapter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("baseline");
            context.getBeanFactory().registerSingleton("appConfig", mock(AppConfig.class));
            context.register(DemoPayChannel.class, PayChannel4Alipay.class, PaymentGatewayPolicy.class);
            context.refresh();
            assertInstanceOf(PayChannel4Alipay.class, context.getBean("payChannel4Alipay"));
        }
    }
}
