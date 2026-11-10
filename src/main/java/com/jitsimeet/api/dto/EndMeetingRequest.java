package com.jitsimeet.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 结束会议请求，使用主持人密钥确认只有主持人可以关闭业务会议。
 *
 * @param hostKey 主持人密钥
 */
public record EndMeetingRequest(
    @NotBlank(message = "主持人密钥不能为空")
    String hostKey
) {
}
