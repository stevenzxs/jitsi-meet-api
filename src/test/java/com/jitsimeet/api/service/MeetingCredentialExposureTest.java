package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.MeetingResponse;
import com.jitsimeet.api.exception.ApiException;
import com.jitsimeet.api.mapper.MeetingBannerMapper;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeetingCredentialExposureTest {
    private final MeetingMapper mapper = mock(MeetingMapper.class);
    private final MeetingService service = new MeetingService(mapper, mock(MeetingBannerMapper.class), mock(JitsiTokenService.class), mock(CreatorRecoveryService.class), mock(HostAuthorityFence.class));

    private Meeting meeting(String status) {
        return new Meeting("123456789", "会议", "password", "主持人", "secret-key", "public-creator",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), status);
    }

    @Test
    void visibleListNeverReturnsCredentialsEvenWithCorrectCreatorId() {
        when(mapper.listVisible("public-creator")).thenReturn(List.of(meeting(Meeting.STATUS_SCHEDULED)));
        MeetingResponse response = service.listVisible("public-creator").get(0);
        assertNull(response.hostKey());
        assertNull(response.password());
    }

    @Test
    void historyNeverReturnsCredentials() {
        when(mapper.listHistory("public-creator")).thenReturn(List.of(meeting(Meeting.STATUS_ENDED)));
        MeetingResponse response = service.listHistory("public-creator").get(0);
        assertNull(response.hostKey());
        assertNull(response.password());
    }

    @Test
    void creatorIdAloneCannotCancelOrDelete() {
        when(mapper.findByRoomName("123456789")).thenReturn(Optional.of(meeting(Meeting.STATUS_ENDED)));
        assertThrows(ApiException.class, () -> service.cancel("123456789", "public-creator"));
        assertThrows(ApiException.class, () -> service.deleteHistory("123456789", "public-creator"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "wrong", "other-room-key", "public-creator" })
    void rejectsCancelAndDeleteWithoutMatchingKey(String key) {
        when(mapper.findByRoomName("123456789")).thenReturn(Optional.of(meeting(Meeting.STATUS_SCHEDULED)));
        assertThrows(ApiException.class, () -> service.cancel("123456789", key));
        assertThrows(ApiException.class, () -> service.deleteHistory("123456789", key));
        verify(mapper, never()).updateById(any(Meeting.class));
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void matchingKeyCanCancelAndDeleteClosedRecord() {
        Meeting meeting = meeting(Meeting.STATUS_SCHEDULED);
        when(mapper.findByRoomName("123456789")).thenReturn(Optional.of(meeting));
        when(mapper.updateStatusAuthorized("123456789", "secret-key", Meeting.STATUS_SCHEDULED,
                Meeting.STATUS_CANCELLED)).thenReturn(1);
        service.cancel("123456789", "secret-key");
        assertEquals(Meeting.STATUS_CANCELLED, meeting.getStatus());
        service.deleteHistory("123456789", "secret-key");
        verify(mapper).deleteById(meeting.getId());
    }
}
