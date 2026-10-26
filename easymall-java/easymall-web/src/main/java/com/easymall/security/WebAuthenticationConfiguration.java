package com.easymall.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.MappedInterceptor;

@Configuration(proxyBeanMethods = false)
public class WebAuthenticationConfiguration {
    @Bean
    WebAuthenticationInterceptor webAuthenticationInterceptor(UserAuthenticationService authentication) {
        return new WebAuthenticationInterceptor(authentication);
    }

    @Bean
    MappedInterceptor webAuthenticationMapping(WebAuthenticationInterceptor interceptor) {
        // 注册为映射拦截器 Bean，同时覆盖 MVC 默认配置之外的处理器映射。
        return new MappedInterceptor(new String[]{"/**"}, interceptor);
    }
}
