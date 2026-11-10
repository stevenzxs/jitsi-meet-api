package com.jitsimeet.api.dto;

import java.time.Instant;

/**
 * 主持人 JWT 响应。
 */
public record HostTokenResponse(
    String token,
    Instant expiresAt
) {
}
