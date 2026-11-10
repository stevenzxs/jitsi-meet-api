package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;

/** Internal credential rotation only. Prosody must authenticate both live occupants
 * and deliver the result privately to the target; this is not a public transfer API.
 */
@Service
public class HostCredentialTransferService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final MeetingMapper meetings;
    private final JitsiTokenService tokens;

    public HostCredentialTransferService(MeetingMapper meetings, JitsiTokenService tokens) {
        this.meetings = meetings;
        this.tokens = tokens;
    }

    public record Result(String hostKey, String identity, String previousOperationId) {
        public Result(String hostKey, String identity) { this(hostKey, identity, null); }
        @Override public String toString() { return "HostTransferResult[redacted]"; }
    }

    @Transactional
    public Result rotate(String roomName, String currentIdentity) {
        if (roomName == null || !roomName.matches("\\d{9}")) throw denied();
        Meeting meeting = meetings.findByRoomName(roomName).orElseThrow(HostCredentialTransferService::denied);
        if (!Meeting.STATUS_RUNNING.equals(meeting.getStatus()) || meeting.getScheduledEndTime() == null
                || !meeting.getScheduledEndTime().isAfter(Instant.now())
                || !tokens.matchesHostIdentity(meeting, currentIdentity)) throw denied();
        String key = newKey();
        // Derive before mutating, so signing/config failure cannot leave credentials changed.
        Meeting target = new Meeting(roomName, meeting.getSubject(), meeting.getPassword(), meeting.getDisplayName(),
                key, meeting.getCreatorId(), meeting.getScheduledStartTime(), meeting.getScheduledEndTime(), meeting.getStatus());
        String identity = tokens.hostIdentity(target);
        if (meetings.rotateHostKey(roomName, meeting.getHostKey(), key, Meeting.STATUS_RUNNING) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Host authority changed; do not retry blindly");
        }
        return new Result(key, identity);
    }

    private String newKey() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) result.append('-');
            result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }

    private static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
}
