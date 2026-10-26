package com.easymall.security;

import com.easymall.controller.AccountController;
import com.easymall.controller.FileController;
import com.easymall.controller.OrderCommentController;
import com.easymall.controller.ProductController;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

/** 所有 MVC 接口默认需要登录，只有清单中同时匹配实际方法和精确路径的接口允许匿名访问。 */
public class WebAuthenticationInterceptor implements HandlerInterceptor {
    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
            endpoint(AccountController.class, "checkCode", "/account/checkCode"),
            endpoint(AccountController.class, "register", "/account/register", String.class, String.class,
                    String.class, String.class, String.class),
            endpoint(AccountController.class, "login", "/account/login", String.class, String.class,
                    String.class, String.class),
            endpoint(ProductController.class, "loadCategory", "/product/loadCategory"),
            endpoint(ProductController.class, "loadCommendProduct", "/product/loadCommendProduct"),
            endpoint(ProductController.class, "loadProduct", "/product/loadProduct", Integer.class, String.class),
            endpoint(ProductController.class, "getProduct", "/product/getProduct", String.class),
            endpoint(ProductController.class, "search", "/product/search", String.class, BigDecimal.class,
                    BigDecimal.class, String.class, String.class, Integer.class),
            endpoint(OrderCommentController.class, "loadComment", "/order/comment/loadComment", String.class, Integer.class),
            endpoint(FileController.class, "getResource", "/file/getResource", HttpServletResponse.class, String.class)
    );
    private static final PublicEndpoint AUTO_LOGIN = endpoint(AccountController.class, "autoLogin", "/account/autoLogin");

    private final UserAuthenticationService authentication;

    public WebAuthenticationInterceptor(UserAuthenticationService authentication) {
        this.authentication = authentication;
    }

    /**
     * 拦截所有请求，判断是否需要登录。
     * @param request
     * @param response
     * @param handler
     * @return
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) { return true; }
        // 清除其他请求分派可能留下的身份，必须根据本次请求的 Token 重新认证。
        request.removeAttribute(CurrentUser.class.getName());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (PUBLIC_ENDPOINTS.stream().anyMatch(endpoint -> endpoint.matches(method, path))) { return true; }

        // 尝试根据 Token 获取登录用户，如果成功则允许访问。
        CurrentUser user = authentication.resolve(request.getHeader(Constants.TOKEN_WEB));
        if (user != null && user.role() == CurrentUser.Role.USER) {
            //如果获取到CurrentUser对象，则设置到请求属性中，以便后续ABaseController中的getTokenUserInfo方法调用
            request.setAttribute(CurrentUser.class.getName(), user);
            return true;
        }
        // 首页在游客未登录时也会调用自动登录接口，保留返回空登录信息的兼容行为。
        if (AUTO_LOGIN.matches(method, path)) { return true; }
        throw new BusinessException(ResponseCodeEnum.CODE_901);
    }

    private static PublicEndpoint endpoint(Class<?> controller, String name, String path, Class<?>... parameters) {
        try {
            return new PublicEndpoint(controller.getMethod(name, parameters), path);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("公开端点声明不再与其控制器匹配", e);
        }
    }

    private record PublicEndpoint(Method method, String path) {
        boolean matches(HandlerMethod handler, String actualPath) {
            return path.equals(actualPath) && method.equals(handler.getMethod());
        }
    }
}
