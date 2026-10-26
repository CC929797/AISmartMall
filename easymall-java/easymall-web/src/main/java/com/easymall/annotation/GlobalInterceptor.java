package com.easymall.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalInterceptor {
    /** 对请求内已认证的身份增加检查；设为 false 也不会让接口允许匿名访问。 */
    boolean checkLogin() default false;
}
