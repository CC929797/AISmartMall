package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.config.AppConfig;
import com.easymall.exception.BusinessException;
import com.easymall.security.ClientIpResolver;
import com.easymall.security.LoginAbuseProtection;
import com.easymall.security.PasswordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminPasswordProtocolTest {
    private final AccountController controller = new AccountController();
    private final AppConfig config = mock(AppConfig.class);
    private final RedisComponent redis = mock(RedisComponent.class);
    private final PasswordService passwords = new PasswordService();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "appConfig", config);
        ReflectionTestUtils.setField(controller, "redisComponent", redis);
        ReflectionTestUtils.setField(controller, "passwordService", passwords);
        ReflectionTestUtils.setField(controller, "loginAbuseProtection", mock(LoginAbuseProtection.class));
        ReflectionTestUtils.setField(controller, "clientIpResolver", new ClientIpResolver(""));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(config.getAdminAccount()).thenReturn("synthetic-admin");
        when(config.getAdminPasswordHash()).thenReturn(passwords.encode("Synthetic123"));
        when(redis.consumeCheckCode("synthetic-code-key")).thenReturn("7");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void originalPasswordCanLoginAgainstHashConfiguration() {
        when(redis.saveTokenInfoAdmin("synthetic-admin")).thenReturn("synthetic-admin-token");
        assertThat(controller.login("synthetic-admin", "Synthetic123", "7", "synthetic-code-key").getCode())
                .isEqualTo(200);
        verify(redis).saveTokenInfoAdmin("synthetic-admin");
    }

    @Test
    void digestCannotReplaceOriginalPassword() {
        String digest = org.apache.commons.codec.digest.DigestUtils.md5Hex("Synthetic123");
        assertThatThrownBy(() -> controller.login("synthetic-admin", digest, "7", "synthetic-code-key"))
                .isInstanceOf(BusinessException.class);
        verify(redis, never()).saveTokenInfoAdmin(anyString());
    }

    @Test
    void missingHashConfigDoesNotFallBackToPlaintextOrMd5() {
        when(config.getAdminPasswordHash()).thenReturn("");
        assertThatThrownBy(() -> controller.login("synthetic-admin", "Synthetic123", "7", "synthetic-code-key"))
                .isInstanceOf(BusinessException.class);
        verify(redis, never()).saveTokenInfoAdmin(anyString());
    }

    @Test
    void consumedCaptchaCannotAuthorizeAnotherAttempt() {
        when(redis.consumeCheckCode("synthetic-code-key")).thenReturn(null);
        assertThatThrownBy(() -> controller.login("synthetic-admin", "Synthetic123", "7", "synthetic-code-key"))
                .isInstanceOf(BusinessException.class);
        verify(redis, never()).saveTokenInfoAdmin(anyString());
    }
}
