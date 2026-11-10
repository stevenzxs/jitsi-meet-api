package com.jitsimeet.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 主持人令牌签发请求，hostKey 用于证明当前用户持有主持人入口。
 */
public record HostTokenRequest(
    @NotBlank String hostKey,
    String displayName
) {
}
