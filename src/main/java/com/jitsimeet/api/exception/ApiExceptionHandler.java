package com.jitsimeet.api.exception;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * API 统一异常处理，保证前端能拿到稳定的业务错误码和错误消息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 处理业务异常。
     *
     * @param exception 业务异常
     * @return 错误响应
     */
    @ExceptionHandler(ApiException.class)
    public ApiErrorResponse handleApiException(ApiException exception) {
        return new ApiErrorResponse(exception.getCode(), exception.getMessage());
    }

    /**
     * 处理请求体校验异常。
     *
     * @param exception 校验异常
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiErrorResponse handleInvalidBody(MethodArgumentNotValidException exception) {
        return new ApiErrorResponse(ApiErrorCode.INVALID_PARAM.getCode(), ApiErrorCode.INVALID_PARAM.getMessage());
    }

    /**
     * 处理路径参数校验异常。
     *
     * @param exception 校验异常
     * @return 错误响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ApiErrorResponse handleInvalidPath(HandlerMethodValidationException exception) {
        return new ApiErrorResponse(ApiErrorCode.INVALID_PARAM.getCode(), ApiErrorCode.INVALID_PARAM.getMessage());
    }
}
