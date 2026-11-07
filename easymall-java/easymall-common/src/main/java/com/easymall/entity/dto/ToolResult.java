package com.easymall.entity.dto;

/** AI 工具的业务结果，供调用方依据结果码判断状态。 */
public record ToolResult<T>(String code, boolean success, boolean retryable, T data, String operationId, String userMessage) {
    public static <T> ToolResult<T> rejected(String code, String userMessage) {
        return new ToolResult<>(code, false, false, null, null, userMessage);
    }
}
