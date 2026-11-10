package com.jitsimeet.api.exception;

/**
 * API 业务错误码，避免业务异常依赖 HTTP 状态语义。
 */
public enum ApiErrorCode {
    INVALID_PARAM(40000, "请求参数不合法"),
    MEETING_NOT_FOUND(40401, "会议不存在"),
    MEETING_FORBIDDEN(40301, "无权操作该会议"),
    MEETING_CLOSED(40901, "会议已结束或已取消"),
    MEETING_EXPIRED(40902, "会议已过期"),
    MEETING_DUPLICATED(40903, "会议号已存在，请重试"),
    MEETING_PASSWORD_REQUIRED(40302, "请输入会议密码"),
    MEETING_PASSWORD_INVALID(40303, "会议密码不正确"),
    HOST_KEY_INVALID(40304, "主持人密钥不正确"),
    TOKEN_CONFIG_INVALID(50001, "Jitsi JWT 签发配置不完整"),
    TOKEN_ISSUE_FAILED(50002, "主持人令牌签发失败");

    private final int code;
    private final String message;

    /**
     * 创建业务错误码。
     *
     * @param code 前端可识别的业务错误码
     * @param message 默认错误提示
     */
    ApiErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
