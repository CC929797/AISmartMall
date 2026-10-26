package com.easymall.controller;

import com.easymall.aspect.GlobalOperationAspect;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserInfo;
import com.easymall.service.UserInfoService;
import com.easymall.security.UserAuthenticationService;
import com.easymall.security.WebAuthenticationConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实登录切面和 MVC 路由，业务服务及 Redis 由内存替身隔离。 */
class UserAccountSecurityTest {
    private static final String USER_A = "ACCOUNT001";
    private static final String USER_B = "ACCOUNT002";
    private static final String TOKEN_A = "synthetic-step01-token";
    private static final String PASSWORD = "synthetic-step01-password";
    private static final String API_KEY = "synthetic-step01-api-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserInfoService users;
    private RedisComponent redis;

    @BeforeEach
    void setUp() {
        assertThat(System.getProperty("dev") == null).as("fixed development identity is disabled").isTrue();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        users = mock(UserInfoService.class);
        redis = mock(RedisComponent.class);
        UserInfo enabled = new UserInfo();
        enabled.setUserId(USER_A);
        enabled.setNickName("A");
        enabled.setAvatar("synthetic-avatar.png");
        enabled.setStatus(1);
        when(users.getUserInfoByUserId(USER_A)).thenReturn(enabled);
        when(redis.getCurrentToken(USER_A)).thenReturn(TOKEN_A);
        // 直接注册测试替身，避免 Spring 向 RedisComponent 的 Mock 对象注入基础设施依赖。
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("userInfoService", users);
            factory.registerSingleton("redisComponent", redis);
        });
        context.register(WebFixture.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void closeContext() {
        if (context != null) { context.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"loadDataList", "add", "addBatch", "addOrUpdateBatch",
            "getUserInfoByUserId", "updateUserInfoByUserId", "deleteUserInfoByUserId",
            "getUserInfoByEmail", "updateUserInfoByEmail", "deleteUserInfoByEmail",
            "getUserInfoByNickName", "updateUserInfoByNickName", "deleteUserInfoByNickName"})
    void generatedCrudHasNoAnonymousHttpMapping(String operation) throws Exception {
        // 发送 JSON 空数组，保证修复前的旧批量接口也能接收到合法请求。
        mvc.perform(get("/userInfo/" + operation).contentType("application/json").content("[]")
                        .param("userId", USER_B).param("email", "b@example.invalid").param("nickName", "B"))
                .andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(users);
    }

    @Test
    void readsOnlyTheTokenOwnerAndReturnsTheExistingProfileContract() throws Exception {
        when(redis.getTokenInfo(TOKEN_A)).thenReturn(session());
        UserInfo owner = new UserInfo();
        owner.setUserId(USER_A);
        owner.setEmail("a@example.invalid");
        owner.setNickName("A");
        owner.setAvatar("synthetic-avatar.png");
        owner.setSex(2);
        owner.setStatus(1);
        owner.setPassword(PASSWORD);
        owner.setApiKey(API_KEY);
        when(users.getUserInfoByUserId(USER_A)).thenReturn(owner);

        String body = mvc.perform(get("/account/getUserInfo").header("token", TOKEN_A).param("userId", USER_B))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(USER_A))
                .andExpect(jsonPath("$.data.email").value("a@example.invalid"))
                .andReturn().getResponse().getContentAsString();
        assertSafe(body);
        assertThat(JSON.readTree(body).path("data").size()).isEqualTo(5);
        verify(users, times(2)).getUserInfoByUserId(USER_A);
    }

    @Test
    void profileMutationCannotSelectAnotherOwnerOrBindCredentialAndAdminFields() throws Exception {
        when(redis.getTokenInfo(TOKEN_A)).thenReturn(session());
        String body = mvc.perform(post("/account/updateUserInfo").header("token", TOKEN_A)
                        .param("avatar", "synthetic-new-avatar.png").param("nickName", "Updated A").param("sex", "1")
                        .param("userId", USER_B).param("email", "attacker@example.invalid").param("status", "0")
                        .param("password", PASSWORD).param("apiKey", API_KEY))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.userId").value(USER_A))
                .andExpect(jsonPath("$.data.nickName").value("Updated A"))
                .andReturn().getResponse().getContentAsString();
        assertSafe(body);

        ArgumentCaptor<UserInfo> update = ArgumentCaptor.forClass(UserInfo.class);
        verify(users).updateUserInfoByUserId(update.capture(), eq(USER_A));
        UserInfo fields = update.getValue();
        assertThat(fields.getAvatar()).isEqualTo("synthetic-new-avatar.png");
        assertThat(fields.getNickName()).isEqualTo("Updated A");
        assertThat(fields.getSex()).isEqualTo(1);
        assertThat(fields.getUserId() == null && fields.getPassword() == null && fields.getApiKey() == null
                && fields.getEmail() == null && fields.getStatus() == null)
                .as("only the three editable profile fields reach the service").isTrue();
        ArgumentCaptor<TokenUserInfoDTO> cached = ArgumentCaptor.forClass(TokenUserInfoDTO.class);
        verify(redis).updateTokenInfo(cached.capture());
        assertThat(cached.getValue().getUserId()).isEqualTo(USER_A);
        assertThat(cached.getValue().getNickName()).isEqualTo("Updated A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/account/getUserInfo", "/account/updateUserInfo"})
    void profileOperationsRejectMissingLogin(String path) throws Exception {
        mvc.perform(post(path).param("avatar", "fixture.png").param("nickName", "A").param("sex", "1"))
                .andExpect(jsonPath("$.code").value(901));
        verifyNoInteractions(users);
    }

    @Test
    void profileReadRejectsAnUnknownToken() throws Exception {
        mvc.perform(get("/account/getUserInfo").header("token", "synthetic-invalid-token"))
                .andExpect(jsonPath("$.code").value(901));
        verifyNoInteractions(users);
    }

    @Test
    void loginAndAutoLoginKeepTheirSafeTokenResponse() throws Exception {
        TokenUserInfoDTO token = session();
        when(redis.getCheckCode("synthetic-check-key")).thenReturn("7");
        when(users.login(eq("a@example.invalid"), eq("synthetic-digest"), anyString())).thenReturn(token);
        String login = mvc.perform(post("/account/login").param("email", "a@example.invalid")
                        .param("password", "synthetic-digest").param("checkCodeKey", "synthetic-check-key")
                        .param("checkCode", "7"))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        assertSafe(login);
        assertThat(JSON.readTree(login).path("data").size()).isEqualTo(4);
        verify(redis).deleteCheckCode("synthetic-check-key");

        when(redis.getTokenInfo(TOKEN_A)).thenReturn(token);
        when(redis.refreshTokenInfo(any())).thenReturn(true);
        String auto = mvc.perform(post("/account/autoLogin").header("token", TOKEN_A))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        assertSafe(auto);
        assertThat(JSON.readTree(auto).path("data").path("userId").asText()).isEqualTo(USER_A);
        ArgumentCaptor<TokenUserInfoDTO> automaticSession = ArgumentCaptor.forClass(TokenUserInfoDTO.class);
        verify(redis).refreshTokenInfo(automaticSession.capture());
        assertThat(automaticSession.getValue().getUserId()).isEqualTo(USER_A);
        assertThat(automaticSession.getValue().getToken()).isEqualTo(TOKEN_A);
        assertThat(automaticSession.getValue().getNickName()).isEqualTo("A");
        assertThat(automaticSession.getValue().getAvatar()).isEqualTo("synthetic-avatar.png");
        assertThat(JSON.readTree(auto).path("data").path("token").asText()).isEqualTo(TOKEN_A);
        verify(redis, never()).saveTokenInfo(any());
    }

    @Test
    void autoLoginCannotRecreateASessionRevokedAfterAuthentication() throws Exception {
        when(redis.getTokenInfo(TOKEN_A)).thenReturn(session());
        when(redis.refreshTokenInfo(any())).thenReturn(false);
        mvc.perform(post("/account/autoLogin").header("token", TOKEN_A))
                .andExpect(jsonPath("$.code").value(901));
        verify(redis, never()).saveTokenInfo(any());
    }

    @Test
    void anonymousAutoLoginRemainsCompatibleWithThePublicHomePage() throws Exception {
        mvc.perform(post("/account/autoLogin"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(users);
    }

    private static TokenUserInfoDTO session() {
        TokenUserInfoDTO token = new TokenUserInfoDTO();
        token.setUserId(USER_A);
        token.setNickName("A");
        token.setAvatar("synthetic-avatar.png");
        token.setToken(TOKEN_A);
        return token;
    }

    private static void assertSafe(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        assertThat(root.findValues("password").isEmpty() && root.findValues("apiKey").isEmpty())
                .as("responses omit both credential fields").isTrue();
        assertThat(!body.contains(PASSWORD) && !body.contains(API_KEY))
                .as("responses omit credential values").isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({WebAuthenticationConfiguration.class, UserAuthenticationService.class})
    static class WebFixture {
        @Bean AccountController accountController() { return new AccountController(); }
        @Bean UserInfoController legacyUserInfoController() { return new UserInfoController(); }
        @Bean GlobalOperationAspect loginAspect() { return new GlobalOperationAspect(); }
        @Bean AGlobalExceptionHandlerController exceptionAdvice() { return new AGlobalExceptionHandlerController(); }
    }
}
