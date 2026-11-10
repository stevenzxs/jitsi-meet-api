package com.jitsimeet.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 参会人在线状态上报请求，用 Jitsi 本地 participantId 做一次会议内的临时身份。
 *
 * @param participantId Jitsi 参会人 ID
 */
public record ParticipantPresenceRequest(
    @NotBlank(message = "参会人 ID 不能为空")
    String participantId
) {
}
