package com.easymall.aspect;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.security.CurrentUser;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
@Slf4j
public class GlobalOperationAspect {
    @Before("@annotation(com.easymall.annotation.GlobalInterceptor)")
    public void interceptor(JoinPoint point) {
        Method method = ((MethodSignature)point.getSignature()).getMethod();
        GlobalInterceptor interceptor = method.getAnnotation(GlobalInterceptor.class);
        if (interceptor == null) {
            return;
        }
        if (interceptor.checkLogin()){
            checkLogin();
        }
    }

    private void checkLogin() {
        HttpServletRequest request = ((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();

        CurrentUser user = (CurrentUser) request.getAttribute(CurrentUser.class.getName());
        if (user == null || user.role() != CurrentUser.Role.USER) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
    }
}
