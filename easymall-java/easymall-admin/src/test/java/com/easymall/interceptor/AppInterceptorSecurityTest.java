package com.easymall.interceptor;

import com.easymall.commonent.RedisComponent;
import com.easymall.controller.AccountController;
import com.easymall.controller.FileController;
import com.easymall.entity.constants.Constants;
import com.easymall.exception.BusinessException;
import com.easymall.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 验证公开接口按实际处理方法识别，不能仅凭请求路径包含特定字符串就放行。 */
class AppInterceptorSecurityTest {
    private final RedisComponent redis = mock(RedisComponent.class);
    private final AppInterceptor interceptor = new AppInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(interceptor, "redisComponent", redis);
    }

    @Test
    void aNewUnannotatedAccountRouteIsProtected() throws Exception {
        assertUnauthorized("/api/account/exportUsers", handler(new FutureController(), "exportUsers"));
        verifyNoInteractions(redis);
    }

    @Test
    void aResourcePrefixDoesNotMakeANewHandlerPublic() throws Exception {
        assertUnauthorized("/api/file/getResourceBackup", handler(new FutureController(), "exportUsers"));
        verifyNoInteractions(redis);
    }

    @Test
    void anAccountSubstringInAnotherRouteDoesNotBypassAuthentication() throws Exception {
        assertUnauthorized("/api/report/account/history", handler(new FutureController(), "exportUsers"));
        verifyNoInteractions(redis);
    }

    @Test
    void evenTheExactPublicUriCannotMakeAnotherHandlerPublic() throws Exception {
        assertUnauthorized("/api/account/login", handler(new FutureController(), "exportUsers"));
        verifyNoInteractions(redis);
    }

    @Test
    void logoutRequiresAnAdminSession() throws Exception {
        assertUnauthorized("/api/account/logout", new HandlerMethod(new AccountController(),
                AccountController.class.getMethod("logout", String.class)));
        verifyNoInteractions(redis);
    }

    @Test
    void onlyActualCaptchaLoginAndImageReadHandlersArePublic() throws Exception {
        AccountController account = new AccountController();
        assertThat(interceptor.preHandle(request("/api/account/checkCode"), response,
                handler(account, "checkCode"))).isTrue();
        assertThat(interceptor.preHandle(request("/api/account/login"), response,
                new HandlerMethod(account, AccountController.class.getMethod("login",
                        String.class, String.class, String.class, String.class)))).isTrue();
        assertThat(interceptor.preHandle(request("/api/file/getResource"), response,
                new HandlerMethod(new FileController(), FileController.class.getMethod("getResource",
                        HttpServletResponse.class, String.class)))).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    void aWebTokenInTheAdminHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = request("/api/user/loadUser");
        request.addHeader(Constants.TOKEN_ADMIN, "synthetic-web-token");
        assertThatThrownBy(() -> interceptor.preHandle(request, response,
                handler(new FutureController(), "exportUsers")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(901));
        verify(redis).getLoginInfo4Admin("synthetic-web-token");
    }

    @Test
    void anExpiredOrLoggedOutAdminTokenIsRejected() throws Exception {
        MockHttpServletRequest request = request("/api/user/loadUser");
        request.addHeader(Constants.TOKEN_ADMIN, "synthetic-expired-token");

        assertThatThrownBy(() -> interceptor.preHandle(request, response,
                handler(new FutureController(), "exportUsers")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(901));
        assertThat(request.getAttribute(CurrentUser.class.getName())).isNull();
    }

    @Test
    void anAdminRequestReceivesAnExplicitAdminIdentity() throws Exception {
        when(redis.getLoginInfo4Admin("synthetic-admin-token")).thenReturn("synthetic-admin");
        MockHttpServletRequest request = request("/api/user/loadUser");
        request.addHeader(Constants.TOKEN_ADMIN, "synthetic-admin-token");

        assertThat(interceptor.preHandle(request, response, handler(new FutureController(), "exportUsers")))
                .isTrue();

        CurrentUser currentUser = (CurrentUser) request.getAttribute(CurrentUser.class.getName());
        assertThat(currentUser.role()).isEqualTo(CurrentUser.Role.ADMIN);
        assertThat(currentUser.userId()).isEqualTo("synthetic-admin");
        verify(redis, times(1)).getLoginInfo4Admin("synthetic-admin-token");
    }

    @Test
    void aPublicHandlerMappedUnderANewPathDoesNotSilentlyExposeIt() throws Exception {
        assertUnauthorized("/api/account/newCaptchaAlias", handler(new AccountController(), "checkCode"));
        verifyNoInteractions(redis);
    }

    private void assertUnauthorized(String uri, HandlerMethod handler) {
        assertThatThrownBy(() -> interceptor.preHandle(request(uri), response, handler))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(901));
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/api");
        return request;
    }

    private static HandlerMethod handler(Object controller, String method) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod(method));
    }

    public static class FutureController {
        public void exportUsers() { }
    }
}
