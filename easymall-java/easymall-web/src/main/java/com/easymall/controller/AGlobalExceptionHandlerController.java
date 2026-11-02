package com.easymall.controller;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class AGlobalExceptionHandlerController extends ABaseController {

    private static final Logger logger = LoggerFactory.getLogger(AGlobalExceptionHandlerController.class);

    @ExceptionHandler(value = Exception.class)
    Object handleException(Exception e, HttpServletRequest request) {
        logger.warn("请求处理失败，路径={}，异常类型={}", request.getRequestURI(), e.getClass().getSimpleName());
        ResponseVO responseVO = new ResponseVO();
        BusinessException business = findBusinessException(e);
        //404
        if (e instanceof NoHandlerFoundException || e instanceof NoResourceFoundException) {
            responseVO.setCode(ResponseCodeEnum.CODE_404.getCode());
            responseVO.setInfo(ResponseCodeEnum.CODE_404.getMsg());
            responseVO.setStatus(STATUC_ERROR);
        } else if (business != null) {
            //业务错误
            BusinessException biz = business;
            responseVO.setCode(biz.getCode() == null ? ResponseCodeEnum.CODE_600.getCode() : biz.getCode());
            responseVO.setInfo(biz.getMessage());
            responseVO.setStatus(STATUC_ERROR);
        } else if (e instanceof BindException || e instanceof MethodArgumentTypeMismatchException || e instanceof HandlerMethodValidationException || e instanceof ConstraintViolationException || e instanceof HttpMessageNotReadableException || e instanceof InvalidPropertyException) {
            //参数类型错误
            responseVO.setCode(ResponseCodeEnum.CODE_600.getCode());
            responseVO.setInfo(ResponseCodeEnum.CODE_600.getMsg());
            responseVO.setStatus(STATUC_ERROR);
        } else if (e instanceof DuplicateKeyException) {
            //主键冲突
            responseVO.setCode(ResponseCodeEnum.CODE_601.getCode());
            responseVO.setInfo(ResponseCodeEnum.CODE_601.getMsg());
            responseVO.setStatus(STATUC_ERROR);
        } else {
            responseVO.setCode(ResponseCodeEnum.CODE_500.getCode());
            responseVO.setInfo(ResponseCodeEnum.CODE_500.getMsg());
            responseVO.setStatus(STATUC_ERROR);
        }
        return responseVO;
    }

    /** 包装层可能隐藏业务拒绝，保留原业务码，不把数据库异常一概当作参数错误。 */
    private BusinessException findBusinessException(Throwable failure) {
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
            if (failure instanceof BusinessException business) { return business; }
        }
        return null;
    }
}
