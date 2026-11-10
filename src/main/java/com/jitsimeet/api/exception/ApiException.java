package com.jitsimeet.api.exception;

/**
 * 业务异常，携带自定义错误码给统一异常处理器返回。
 */
public class ApiException extends RuntimeException {
    private final int code;

    /**
     * 创建业务异常。
     *
     * @param errorCode 业务错误码
     */
    public ApiException(ApiErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /**
     * 创建业务异常，并覆盖默认提示。
     *
     * @param errorCode 业务错误码
     * @param message 错误信息
     */
    public ApiException(ApiErrorCode errorCode, String message) {
        this(errorCode.getCode(), message);
    }

    /**
     * 创建业务异常，兼容少量需要临时指定错误码的场景。
     *
     * @param code 业务错误码
     * @param message 错误信息
     */
    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
