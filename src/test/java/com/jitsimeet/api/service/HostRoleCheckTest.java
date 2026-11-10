package com.jitsimeet.api.service;

import com.jitsimeet.api.config.JitsiTokenProperties;
import com.jitsimeet.api.controller.HostRoleCheckController;
import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostRoleCheckTest {
    private final JitsiTokenProperties config = config();
    private final MeetingMapper meetings = mock(MeetingMapper.class);
    private final HostAuthorityFence fence = mock(HostAuthorityFence.class);
    private final JitsiTokenService tokens = new JitsiTokenService(config);
    private final HostRoleCheckController controller = new HostRoleCheckController(meetings, tokens, config, fence);
    private final String secret = "test-only-role-channel-secret-32-characters";

    @Test void noSecretOrWrongSecretNeverReadsMeeting() {
        assertThrows(ResponseStatusException.class, () -> controller.check(null, null));
        assertThrows(ResponseStatusException.class, () -> controller.check("wrong", null));
        config.setRoleCheckSecret("");
        assertThrows(ResponseStatusException.class, () -> controller.check(secret, null));
        verifyNoInteractions(meetings);
    }

    @Test void rotationRejectsPreviouslySignedIdentity() {
        when(fence.isActive("123456789")).thenReturn(true);
        Meeting original = meeting("old-key", Meeting.STATUS_RUNNING);
        String identity = tokens.hostIdentity(original);
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(original));
        var request = new HostRoleCheckController.Check("123456789", identity);
        assertTrue(controller.check(secret, request));
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting("new-key", Meeting.STATUS_RUNNING)));
        assertFalse(controller.check(secret, request));
    }

    @Test void closedRoomAndForgedIdentityAreDenied() {
        when(fence.isActive("123456789")).thenReturn(true);
        var meeting = meeting("key", Meeting.STATUS_ENDED);
        when(meetings.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
        assertFalse(controller.check(secret, new HostRoleCheckController.Check("123456789", tokens.hostIdentity(meeting))));
        assertFalse(controller.check(secret, new HostRoleCheckController.Check("other", "forged")));
    }

    @Test void transferFenceRejectsEvenNewHostIdentity() {
        var meeting = meeting("new-key", Meeting.STATUS_RUNNING);
        assertFalse(controller.check(secret,
                new HostRoleCheckController.Check("123456789", tokens.hostIdentity(meeting))));
        verify(fence).isActive("123456789");
        verifyNoInteractions(meetings);
    }

    private Meeting meeting(String key, String status) {
        return new Meeting("123456789", "主题", "", "A", key, "creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), status);
    }
    private JitsiTokenProperties config() {
        var p = new JitsiTokenProperties();
        p.setAppId("test"); p.setAppSecret("test-signing-secret"); p.setDomain("test"); p.setTtlHours(1);
        p.setRoleCheckSecret("test-only-role-channel-secret-32-characters"); return p;
    }
}
