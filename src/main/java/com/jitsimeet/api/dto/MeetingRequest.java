package com.jitsimeet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * 创建即时会议和预定会议的请求模型。
 */
public record MeetingRequest(
    @NotBlank @Pattern(regexp = "\\d{9}") String roomName,
    @NotBlank String subject,
    String password,
    String displayName,
    String hostKey,
    String creatorId,
    String creatorRecoveryKey,
    Instant scheduledStartTime,
    @Min(5) @Max(1440) Integer durationMinutes
) {
}
