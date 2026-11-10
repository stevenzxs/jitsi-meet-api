package com.jitsimeet.api.controller;

import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import com.jitsimeet.api.service.HostAuthorityFence;
import com.jitsimeet.api.service.JitsiTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Server-to-server only. Identity is public, so never authorize this route by identity alone. */
@RestController
@RequestMapping("/internal/host-role/v1")
public class HostRoleCheckController {
    private final MeetingMapper meetings;
    private final JitsiTokenService tokens;
    private final JitsiTokenProperties properties;
    private final HostAuthorityFence fence;

    public HostRoleCheckController(MeetingMapper meetings, JitsiTokenService tokens, JitsiTokenProperties properties,
                                   HostAuthorityFence fence) {
        this.meetings = meetings;
        this.tokens = tokens;
        this.properties = properties;
        this.fence = fence;
    }

    public record Check(String roomName, String identity) {}

    @PostMapping("/check")
    public boolean check(@RequestHeader(value = "X-Role-Check-Secret", required = false) String secret,
                         @RequestBody Check request) {
        authenticate(secret);
        if (request == null || request.roomName() == null || !request.roomName().matches("\\d{9}")) return false;
        if (!fence.isActive(request.roomName())) return false;
        return meetings.findByRoomName(request.roomName()).filter(meeting ->
                !Meeting.STATUS_ENDED.equals(meeting.getStatus()) && !Meeting.STATUS_CANCELLED.equals(meeting.getStatus())
                && meeting.getScheduledEndTime() != null && meeting.getScheduledEndTime().isAfter(Instant.now()))
                .map(meeting -> tokens.matchesHostIdentity(meeting, request.identity())).orElse(false);
    }

    public void authenticate(String secret) {
        String expected = properties.getRoleCheckSecret();
        if (expected == null || expected.length() < 32 || secret == null || secret.length() > 256
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
