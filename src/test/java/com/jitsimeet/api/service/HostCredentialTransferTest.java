package com.jitsimeet.api.service;

import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HostCredentialTransferTest {
    private final MeetingMapper meetings = mock(MeetingMapper.class);
    private final JitsiTokenService tokens = tokens();
    private final HostCredentialTransferService service = new HostCredentialTransferService(meetings, tokens);

    @Test void rotationChangesIdentityAndDoesNotOverwriteSubject() {
        var meeting = meeting(Meeting.STATUS_RUNNING);
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
        when(meetings.rotateHostKey(eq("123456789"), eq("old-key"), anyString(), eq(Meeting.STATUS_RUNNING))).thenReturn(1);
        var result = service.rotate("123456789", tokens.hostIdentity(meeting));
        assertTrue(result.hostKey().matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}"));
        assertNotEquals(tokens.hostIdentity(meeting), result.identity());
        assertFalse(result.toString().contains(result.hostKey()));
        verify(meetings, never()).updateById(any(Meeting.class));
        assertEquals("主题", meeting.getSubject());
    }

    @Test void staleIdentityCannotRotate() {
        var meeting = meeting(Meeting.STATUS_RUNNING);
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
        assertThrows(ResponseStatusException.class, () -> service.rotate("123456789", "forged"));
        verify(meetings, never()).rotateHostKey(anyString(), anyString(), anyString(), anyString());
    }

    @Test void failedCompareAndSwapNeverReturnsNewCredentials() {
        var meeting = meeting(Meeting.STATUS_RUNNING);
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
        var error = assertThrows(ResponseStatusException.class, () -> service.rotate("123456789", tokens.hostIdentity(meeting)));
        assertEquals(409, error.getStatusCode().value());
    }

    @Test void scheduledAndClosedMeetingsCannotTransfer() {
        for (String status : new String[]{Meeting.STATUS_SCHEDULED, Meeting.STATUS_ENDED, Meeting.STATUS_CANCELLED}) {
            var meeting = meeting(status);
            when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
            assertThrows(ResponseStatusException.class, () -> service.rotate("123456789", tokens.hostIdentity(meeting)));
        }
        verify(meetings, never()).rotateHostKey(anyString(), anyString(), anyString(), anyString());
    }

    private Meeting meeting(String status) {
        return new Meeting("123456789", "主题", "", "A", "old-key", "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), status);
    }
    private JitsiTokenService tokens() {
        var config = new JitsiTokenProperties(); config.setAppId("test"); config.setAppSecret("test-secret");
        config.setDomain("test"); config.setTtlHours(1); return new JitsiTokenService(config);
    }
}
