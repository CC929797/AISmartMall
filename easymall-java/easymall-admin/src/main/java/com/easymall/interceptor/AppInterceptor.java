package com.easymall.interceptor;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import com.easymall.controller.AccountController;
import com.easymall.controller.FileController;
import com.easymall.security.CurrentUser;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Map;

@Component
public class AppInterceptor implements HandlerInterceptor {

    private static final Map<Method, String> PUBLIC_HANDLERS = publicHandlers();

    @Resource
    private RedisComponent redisComponent;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.removeAttribute(CurrentUser.class.getName());
        if (null == handler) {
            return false;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String publicPath = PUBLIC_HANDLERS.get(handlerMethod.getMethod());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (publicPath != null && publicPath.equals(path)) {
            return true;
        }
        String token = request.getHeader(Constants.TOKEN_ADMIN);
        if (StringTools.isEmpty(token)) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        String account = redisComponent.getLoginInfo4Admin(token);
        if (StringTools.isEmpty(account)) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        request.setAttribute(CurrentUser.class.getName(),
                new CurrentUser(account, account, null, CurrentUser.Role.ADMIN));
        return true;
    }

    private static Map<Method, String> publicHandlers() {
        try {
            return Map.of(
                    AccountController.class.getMethod("checkCode"), "/account/checkCode",
                    AccountController.class.getMethod("login", String.class, String.class,
                            String.class, String.class), "/account/login",
                    FileController.class.getMethod("getResource", HttpServletResponse.class, String.class),
                    "/file/getResource");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("后台管理模块的公开处理器配置，和实际 Controller 的路由/端点定义不一致", e);
        }
    }
}
