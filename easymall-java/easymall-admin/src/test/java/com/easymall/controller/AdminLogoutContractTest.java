package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.interceptor.AppInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLogoutContractTest {
    private static final String TOKEN = "synthetic-admin-logout-token";
    private final RedisComponent redis = mock(RedisComponent.class);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController();
        AppInterceptor interceptor = new AppInterceptor();
        ReflectionTestUtils.setField(controller, "redisComponent", redis);
        ReflectionTestUtils.setField(interceptor, "redisComponent", redis);
        when(redis.getLoginInfo4Admin(TOKEN)).thenAnswer(call -> active.get() ? "synthetic-admin" : null);
        doAnswer(call -> { active.set(false); return null; }).when(redis).cleanTokenInfoAdmin(TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
    }

    @Test
    void firstLogoutReturnsStandardSuccessBodyAndRevokesSession() throws Exception {
        mvc.perform(post("/account/logout").header(Constants.TOKEN_ADMIN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.status").value("success"));
        verify(redis, times(1)).cleanTokenInfoAdmin(TOKEN);
        mvc.perform(post("/account/logout").header(Constants.TOKEN_ADMIN, TOKEN))
                .andExpect(jsonPath("$.code").value(901));
        verify(redis, times(1)).cleanTokenInfoAdmin(TOKEN);
    }

    @Test
    void anonymousLogoutRemainsProtected() throws Exception {
        mvc.perform(post("/account/logout"))
                .andExpect(jsonPath("$.code").value(901));
        verify(redis, never()).cleanTokenInfoAdmin(anyString());
    }

    @Test
    void expiredLogoutRemainsAnAuthenticationFailure() throws Exception {
        active.set(false);
        mvc.perform(post("/account/logout").header(Constants.TOKEN_ADMIN, TOKEN))
                .andExpect(jsonPath("$.code").value(901));
        verify(redis, never()).cleanTokenInfoAdmin(anyString());
    }
}
