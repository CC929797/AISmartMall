package com.easymall.validation;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** Service 直接调用时也必须满足的输入约束，不依赖 HTTP 或 AOP 上下文。 */
public final class InputValidator {
    private InputValidator() { }

    public static void optionalPageNo(Integer pageNo) {
        if (pageNo != null) { requireRange(pageNo, 1, InputLimits.MAX_PAGE_NO); }
    }

    public static void optionalPageSize(Integer pageSize) {
        if (pageSize != null) { requireRange(pageSize, 1, InputLimits.MAX_PAGE_SIZE); }
    }

    public static void requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) { reject(); }
    }

    public static void requireRange(Integer value, int minimum, int maximum) {
        if (value == null || value < minimum || value > maximum) { reject(); }
    }

    public static void validateBean(Object value) {
        if (value == null || !ValidatorHolder.VALIDATOR.validate(value).isEmpty()) { reject(); }
    }

    private static void reject() { throw new BusinessException(ResponseCodeEnum.CODE_600); }

    /** 延迟创建可跨线程复用的校验器，不启动 Spring 或任何外部服务。 */
    private static final class ValidatorHolder {
        private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    }
}
