package com.easymall.controller;

import com.easymall.aspect.GlobalOperationAspect;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.po.UserInfo;
import com.easymall.security.UserAuthenticationService;
import com.easymall.security.WebAuthenticationConfiguration;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.UserInfoService;
import com.easymall.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 验证 HTTP 鉴权边界，包括新增接口未添加登录注解时仍受保护的场景。 */
class WebAuthenticationBoundaryTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RedisComponent redis;
    private FileUtils files;
    private UserInfoService users;

    @BeforeEach
    void start() {
        redis = mock(RedisComponent.class);
        files = mock(FileUtils.class);
        users = mock(UserInfoService.class);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("redisComponent", redis);
            factory.registerSingleton("userInfoService", users);
            factory.registerSingleton("fileUtils", files);
            factory.registerSingleton("appConfig", mock(AppConfig.class));
        });
        context.register(Fixture.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void stop() { if (context != null) { context.close(); } }

    @ParameterizedTest
    @ValueSource(strings = {"/future/private", "/account/futurePrivate", "/product/futurePrivate",
            "/file/getResource/futurePrivate"})
    void unannotatedHandlersAreProtectedEvenUnderPublicPrefixes(String path) throws Exception {
        mvc.perform(post(path)).andExpect(jsonPath("$.code").value(901));
        verifyNoInteractions(redis);
    }

    @Test
    void anonymousUploadNeverReachesFileStorage() throws Exception {
        when(files.uploadImage(any(), anyBoolean())).thenReturn("synthetic.png");
        mvc.perform(multipart("/file/uploadImage")
                        .file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{1}))
                        .param("createThumbnail", "false"))
                .andExpect(jsonPath("$.code").value(901));
        verifyNoInteractions(files);
    }

    @Test
    void devSystemPropertyCannotCreateAnAuthenticatedUser() throws Exception {
        String original = System.getProperty("dev");
        try {
            System.setProperty("dev", "true");
            mvc.perform(post("/account/getUserInfo")).andExpect(jsonPath("$.code").value(901));
            verifyNoInteractions(redis);
        } finally {
            if (original == null) { System.clearProperty("dev"); } else { System.setProperty("dev", original); }
        }
    }

    @Test
    void repeatedIdentityReadsShareOneAuthentication() throws Exception {
        TokenUserInfoDTO token = new TokenUserInfoDTO();
        token.setUserId("BOUNDARY001");
        token.setNickName("Synthetic A");
        token.setToken("synthetic-boundary-token");
        when(redis.getTokenInfo(token.getToken())).thenReturn(token);
        when(redis.getCurrentToken(token.getUserId())).thenReturn(token.getToken());
        UserInfo owner = new UserInfo();
        owner.setUserId(token.getUserId());
        owner.setStatus(1);
        when(users.getUserInfoByUserId(token.getUserId())).thenReturn(owner);
        mvc.perform(post("/future/identity").header("token", token.getToken()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("BOUNDARY001:BOUNDARY001"));
        verify(redis, times(1)).getTokenInfo(token.getToken());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({WebAuthenticationConfiguration.class, UserAuthenticationService.class})
    static class Fixture {
        @Bean AccountController accountController() { return new AccountController(); }
        @Bean FileController fileController() { return new FileController(); }
        @Bean ProbeController probeController() { return new ProbeController(); }
        @Bean GlobalOperationAspect loginAspect() { return new GlobalOperationAspect(); }
        @Bean AGlobalExceptionHandlerController advice() { return new AGlobalExceptionHandlerController(); }
    }

    @RestController
    static class ProbeController extends ABaseController {
        @RequestMapping({"/future/private", "/account/futurePrivate", "/product/futurePrivate",
                "/file/getResource/futurePrivate"})
        public ResponseVO privateOperation() { return getSuccessResponseVO("private"); }

        @RequestMapping("/future/identity")
        public ResponseVO identity() {
            return getSuccessResponseVO(getTokenUserInfo().getUserId() + ":" + getTokenUserInfo().getUserId());
        }
    }
}
