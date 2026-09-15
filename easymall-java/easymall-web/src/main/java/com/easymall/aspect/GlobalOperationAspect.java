package com.easymall.aspect;

import com.easymall.annotation.GlobalInterceptor;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import com.easymall.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletRequest;
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
    @Resource
    private RedisComponent redisComponent;

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

        if(System.getProperty("dev")!=null){
            TokenUserInfoDTO tokenUserInfoDTO = new TokenUserInfoDTO();
            tokenUserInfoDTO = new TokenUserInfoDTO();
            tokenUserInfoDTO.setUserId("5336131822");
            tokenUserInfoDTO.setNickName("test001");
            tokenUserInfoDTO.setToken("test");
            redisComponent.saveTokenInfo(tokenUserInfoDTO);
            return;
        }

        String token = request.getHeader(Constants.TOKEN_WEB);
        if(StringTools.isEmpty(token)){
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        TokenUserInfoDTO tokenUserInfoDTO = redisComponent.getTokenInfo(token);

        if(tokenUserInfoDTO == null){
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
    }
}
