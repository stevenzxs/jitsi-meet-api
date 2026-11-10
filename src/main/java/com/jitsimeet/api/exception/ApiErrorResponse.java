package com.jitsimeet.api.exception;

/**
 * API 异常响应体，字段名与前端 request.ts 的统一响应解析保持一致。
 *
 * @param code 业务错误码
 * @param message 错误提示
 */
public record ApiErrorResponse(
    int code,
    String message
) {
}
