package com.jitsimeet.api.service;

import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.HostTokenResponse;
import com.jitsimeet.api.exception.ApiErrorCode;
import com.jitsimeet.api.exception.ApiException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.security.MessageDigest;

/**
 * Jitsi 主持人 JWT 签发服务，替代前端本地 HS256 签名逻辑。
 */
@Service
public class JitsiTokenService {
    private final JitsiTokenProperties properties;

    /**
     * 注入签发配置。
     *
     * @param properties JWT 签发配置
     */
    public JitsiTokenService(JitsiTokenProperties properties) {
        this.properties = properties;
    }

    /**
     * 签发主持人令牌，过期时间取配置 TTL 与会议结束时间的较小值。
     *
     * @param meeting 会议记录
     * @param displayName 主持人显示名
     * @return JWT 与过期时间
     */
    public HostTokenResponse issueHostToken(Meeting meeting, String displayName) {
        ensureTokenConfig();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTtlHours(), ChronoUnit.HOURS);
        if (meeting.getScheduledEndTime() != null && meeting.getScheduledEndTime().isBefore(expiresAt)) {
            expiresAt = meeting.getScheduledEndTime();
        }
        String safeName = normalizeDisplayName(displayName);
        long nowSec = now.getEpochSecond();
        long expSec = expiresAt.getEpochSecond();
        if (expSec <= nowSec) {
            throw new ApiException(ApiErrorCode.MEETING_EXPIRED);
        }
        String payload = "{"
            + "\"iss\":\"" + json(properties.getAppId()) + "\","
            + "\"aud\":\"" + json(properties.getAppId()) + "\","
            + "\"sub\":\"" + json(properties.getDomain()) + "\","
            + "\"room\":\"" + json(meeting.getRoomName()) + "\","
            + "\"iat\":" + nowSec + ","
            + "\"exp\":" + expSec + ","
            + "\"context\":{\"user\":{\"id\":\"" + hostIdentity(meeting) + "\",\"name\":\"" + json(safeName) + "\",\"moderator\":true,\"affiliation\":\"owner\"}}"
            + "}";
        return new HostTokenResponse(sign(payload), expiresAt);
    }

    private void ensureTokenConfig() {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret()) || isBlank(properties.getDomain())
                || properties.getTtlHours() <= 0) {
            throw new ApiException(ApiErrorCode.TOKEN_CONFIG_INVALID);
        }
    }

    /** Opaque, room-scoped identity. Rotating the management credential revokes it.
     * Not a bearer credential: callers still need a verified transport/token.
     */
    public String hostIdentity(Meeting meeting) {
        ensureTokenConfig();
        if (isBlank(meeting.getHostKey())) throw new ApiException(ApiErrorCode.HOST_KEY_INVALID);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String input = "host-identity-v1\n" + meeting.getRoomName() + "\n" + meeting.getHostKey();
            return "host-v1-" + base64Url(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.TOKEN_ISSUE_FAILED);
        }
    }

    public boolean matchesHostIdentity(Meeting meeting, String identity) {
        if (identity == null || identity.length() != 51 || isBlank(meeting.getHostKey())) return false;
        return MessageDigest.isEqual(hostIdentity(meeting).getBytes(StandardCharsets.UTF_8),
                identity.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String payload) {
        try {
            String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String content = base64Url(header.getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(payload.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return content + "." + base64Url(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.TOKEN_ISSUE_FAILED);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeDisplayName(String displayName) {
        String value = isBlank(displayName) ? "主持人" : displayName.trim();
        return value.length() > 20 ? value.substring(0, 20) : value;
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
