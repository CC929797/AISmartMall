package com.easymall.validation;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 约束所有商城 MVC 入口的绑定范围，内部查询结构不能由请求参数设置。 */
@ControllerAdvice(basePackages = "com.easymall.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestInputAdvice {
    @InitBinder
    public void restrictBinding(WebDataBinder binder, NativeWebRequest request) {
        Set<String> disallowed = new LinkedHashSet<>();
        if (binder.getDisallowedFields() != null) { disallowed.addAll(Arrays.asList(binder.getDisallowedFields())); }
        disallowed.addAll(Set.of("orderBy", "*.orderBy", "simplePage", "simplePage.*", "*.simplePage.*",
                "class.*", "*.class.*"));
        binder.setDisallowedFields(disallowed.toArray(String[]::new));
        // 在 Bean Validation 执行前也限制表单集合自动扩容，避免超大下标申请大量内存。
        binder.setAutoGrowCollectionLimit(InputLimits.MAX_PAGE_SIZE);

        HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
        boolean messageEndpoint = servletRequest != null && "/agent/sendMessage".equals(
                servletRequest.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            String name = canonicalName(parameter.getKey());
            if (Arrays.stream(name.split("\\.")).anyMatch(part -> part.equals("orderby") || part.equals("simplepage"))) {
                reject();
            }
            if (name.equals("pageno")) { checkPage(parameter.getValue(), true); }
            if (name.equals("pagesize")) { checkPage(parameter.getValue(), false); }
            if (messageEndpoint && (name.startsWith("attachment") || name.startsWith("file"))) { reject(); }
        }
        MultipartHttpServletRequest multipart = request.getNativeRequest(MultipartHttpServletRequest.class);
        if (messageEndpoint && multipart != null && !multipart.getMultiFileMap().isEmpty()) { reject(); }
    }

    private String canonicalName(String name) {
        String result = name.toLowerCase(Locale.ROOT);
        while (result.startsWith("_") || result.startsWith("!")) { result = result.substring(1); }
        // Spring 允许 pageNo[] 作为标量参数的回退名称，也必须按同一分页边界检查。
        return result.replace("[]", "").replace("[", ".").replace("]", "").replace("'", "").replace("\"", "");
    }

    private void checkPage(String[] values, boolean pageNumber) {
        if (values == null || values.length != 1) { reject(); }
        // 缺省和空字符串保持现有前端的可选分页语义，显式数值必须在范围内。
        if (values[0] == null || values[0].isBlank()) { return; }
        try {
            int value = Integer.parseInt(values[0]);
            if (pageNumber) { InputValidator.optionalPageNo(value); }
            else { InputValidator.optionalPageSize(value); }
        } catch (NumberFormatException e) { reject(); }
    }

    private void reject() { throw new BusinessException(ResponseCodeEnum.CODE_600); }
}
