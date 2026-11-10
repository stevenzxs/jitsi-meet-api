package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.HostTokenRequest;
import com.jitsimeet.api.dto.HostTokenResponse;
import com.jitsimeet.api.exception.ApiErrorCode;
import com.jitsimeet.api.exception.ApiException;
import com.jitsimeet.api.mapper.MeetingBannerMapper;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTokenAuthorizationTest {
    private static final String ROOM = "123456789";
    private static final String KEY = "ABCD-EFGH-JKLM-NPQR";
    private final MeetingMapper mapper = mock(MeetingMapper.class);
    private final JitsiTokenService tokens = mock(JitsiTokenService.class);
    private final MeetingService service = new MeetingService(mapper, mock(MeetingBannerMapper.class), tokens, mock(CreatorRecoveryService.class), mock(HostAuthorityFence.class));
    private Meeting meeting;

    @BeforeEach
    void setUp() {
        meeting = meeting(Meeting.STATUS_RUNNING, KEY, Instant.now().plusSeconds(3600));
        when(mapper.findByRoomName(ROOM)).thenAnswer(invocation -> Optional.of(meeting));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "wrong", "WXYZ-2345-6789-ABCD" })
    void rejectsMissingWrongAndOtherRoomKeys(String key) {
        assertRejected(key, ApiErrorCode.HOST_KEY_INVALID);
    }

    @Test
    void rejectsNullRequest() {
        ApiException error = assertThrows(ApiException.class, () -> service.hostToken(ROOM, null));
        assertEquals(ApiErrorCode.HOST_KEY_INVALID.getCode(), error.getCode());
        verifyNoInteractions(tokens);
    }

    @Test
    void rejectsMeetingWithoutStoredKey() {
        meeting = meeting(Meeting.STATUS_RUNNING, null, Instant.now().plusSeconds(3600));
        assertRejected(KEY, ApiErrorCode.HOST_KEY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = { Meeting.STATUS_ENDED, Meeting.STATUS_CANCELLED })
    void rejectsClosedMeetings(String status) {
        meeting = meeting(status, KEY, Instant.now().plusSeconds(3600));
        assertRejected(KEY, ApiErrorCode.MEETING_CLOSED);
    }

    @Test
    void rejectsExpiredMeetings() {
        meeting = meeting(Meeting.STATUS_RUNNING, KEY, Instant.now().minusSeconds(1));
        assertRejected(KEY, ApiErrorCode.MEETING_EXPIRED);
    }

    @Test
    void issuesTokenOnlyForValidKey() {
        HostTokenResponse response = new HostTokenResponse("signed-token", Instant.now().plusSeconds(60));
        when(tokens.issueHostToken(meeting, "主持人")).thenReturn(response);
        assertSame(response, service.hostToken(ROOM, new HostTokenRequest(" " + KEY + " ", "主持人")));
        verify(tokens).issueHostToken(meeting, "主持人");
    }

    @Test
    void preservesExistingScheduledEarlyJoinPolicy() {
        meeting = new Meeting(ROOM, "会议", "", "主持人", KEY, "creator",
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600), Meeting.STATUS_SCHEDULED);
        service.hostToken(ROOM, new HostTokenRequest(KEY, "主持人"));
        verify(tokens).issueHostToken(meeting, "主持人");
    }

    private void assertRejected(String key, ApiErrorCode expected) {
        ApiException error = assertThrows(ApiException.class,
                () -> service.hostToken(ROOM, new HostTokenRequest(key, "主持人")));
        assertEquals(expected.getCode(), error.getCode());
        verifyNoInteractions(tokens);
    }

    private Meeting meeting(String status, String key, Instant end) {
        return new Meeting(ROOM, "会议", "", "主持人", key, "creator",
                Instant.now().minusSeconds(60), end, status);
    }
}
