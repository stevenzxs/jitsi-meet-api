package com.jitsimeet.api.dto;

import java.time.Instant;

/**
 * 桌面端加入前校验通过后返回的安全会议信息。
 * 这里不回传服务端保存的密码，避免校验接口变成密码泄露入口。
 *
 * @param roomName 会议号
 * @param subject 会议主题
 * @param status 会议状态
 * @param scheduledEndTime 会议结束时间
 * @param passwordRequired 是否需要会议密码
 */
public record JoinMeetingResponse(
    String roomName,
    String subject,
    String status,
    Instant scheduledEndTime,
    boolean passwordRequired
) {
}
