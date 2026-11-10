package com.jitsimeet.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.HostTokenResponse;
import com.jitsimeet.api.exception.ApiErrorCode;
import com.jitsimeet.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JitsiTokenServiceTest {
    private final JitsiTokenProperties properties = properties();
    private final JitsiTokenService service = new JitsiTokenService(properties);

    @ParameterizedTest
    @ValueSource(longs = { 0, -1 })
    void rejectsNonPositiveTtl(long ttl) {
        properties.setTtlHours(ttl);
        ApiException error = assertThrows(ApiException.class,
                () -> service.issueHostToken(meeting(Instant.now().plusSeconds(3600)), "主持人"));
        assertEquals(ApiErrorCode.TOKEN_CONFIG_INVALID.getCode(), error.getCode());
    }

    @Test
    void refusesToSignExpiredMeeting() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.issueHostToken(meeting(Instant.now().minusSeconds(1)), "主持人"));
        assertEquals(ApiErrorCode.MEETING_EXPIRED.getCode(), error.getCode());
    }

    @Test
    void bindsRoomModeratorExpiryAndSignature() throws Exception {
        Instant end = Instant.now().plusSeconds(600);
        HostTokenResponse response = service.issueHostToken(meeting(end), "主持人");
        String[] parts = response.token().split("\\.");
        JsonNode payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("123456789", payload.path("room").asText());
        assertTrue(payload.path("context").path("user").path("moderator").asBoolean());
        assertEquals("owner", payload.path("context").path("user").path("affiliation").asText());
        assertEquals(end.getEpochSecond(), payload.path("exp").asLong());
        assertTrue(payload.path("exp").asLong() > payload.path("iat").asLong());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getAppSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertArrayEquals(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlDecoder().decode(parts[2]));
    }

    private Meeting meeting(Instant end) {
        return new Meeting("123456789", "会议", "", "主持人", "key", "creator",
                Instant.now().minusSeconds(60), end, Meeting.STATUS_RUNNING);
    }

    @Test
    void hostIdentityIsStableForCredentialAndChangesAfterRotation() throws Exception {
        var meeting = meeting(Instant.now().plusSeconds(600));
        var json = new ObjectMapper();
        var first = json.readTree(Base64.getUrlDecoder().decode(service.issueHostToken(meeting, "A").token().split("\\.")[1]));
        var second = json.readTree(Base64.getUrlDecoder().decode(service.issueHostToken(meeting, "B").token().split("\\.")[1]));
        String identity = first.path("context").path("user").path("id").asText();
        assertFalse(identity.isBlank());
        assertEquals(identity, second.path("context").path("user").path("id").asText());
        assertFalse(identity.contains(meeting.getHostKey()));
        var rotated = new Meeting("123456789", "会议", "", "主持人", "rotated-secret", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), Meeting.STATUS_RUNNING);
        var third = json.readTree(Base64.getUrlDecoder().decode(service.issueHostToken(rotated, "A").token().split("\\.")[1]));
        assertNotEquals(identity, third.path("context").path("user").path("id").asText());
    }

    private JitsiTokenProperties properties() {
        JitsiTokenProperties result = new JitsiTokenProperties();
        result.setAppId("test-app");
        result.setAppSecret("test-only-signing-secret");
        result.setDomain("meet.jitsi");
        result.setTtlHours(1);
        return result;
    }
}
