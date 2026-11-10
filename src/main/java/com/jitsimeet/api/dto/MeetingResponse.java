package com.jitsimeet.api.dto;

import java.time.Instant;

/**
 * 会议响应模型，字段名直接匹配前端 meetingApi.ts 的数据结构。
 */
public record MeetingResponse(
    String id,
    String roomName,
    String subject,
    String status,
    Instant scheduledStartTime,
    Instant scheduledEndTime,
    Instant startedAt,
    Instant endedAt,
    String password,
    String displayName,
    String hostKey,
    String creatorId
) {
}
