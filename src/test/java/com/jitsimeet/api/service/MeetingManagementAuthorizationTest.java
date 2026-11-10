package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.dto.EndMeetingRequest;
import com.jitsimeet.api.dto.MeetingBannerRequest;
import com.jitsimeet.api.exception.ApiErrorCode;
import com.jitsimeet.api.exception.ApiException;
import com.jitsimeet.api.mapper.MeetingBannerMapper;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MeetingManagementAuthorizationTest {
    private static final String ROOM = "123456789";
    private static final String KEY = "ABCD-EFGH-JKLM-NPQR";
    private final MeetingMapper mapper = mock(MeetingMapper.class);
    private final MeetingBannerMapper banners = mock(MeetingBannerMapper.class);
    private final MeetingService service = new MeetingService(mapper, banners, mock(JitsiTokenService.class), mock(CreatorRecoveryService.class), activeFence());

    private static HostAuthorityFence activeFence() {
        var fence = mock(HostAuthorityFence.class);
        when(fence.isActive(anyString())).thenReturn(true);
        return fence;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "wrong", "OTHER-ROOM-KEY" })
    void deniesAllManagementOperationsWithInvalidKeys(String key) {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertError(ApiErrorCode.HOST_KEY_INVALID, () -> service.end(ROOM, new EndMeetingRequest(key)));
        assertError(ApiErrorCode.HOST_KEY_INVALID, () -> service.saveBanner(ROOM, banner(key)));
        assertFalse(service.isBannerWritable(ROOM, key));
        verify(mapper, never()).updateById(any(Meeting.class));
        verifyNoInteractions(banners);
    }

    @ParameterizedTest
    @ValueSource(strings = { Meeting.STATUS_ENDED, Meeting.STATUS_CANCELLED })
    void closedMeetingsRequireAuthorizationEvenForIdempotentEnd(String status) {
        givenMeeting(status, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertError(ApiErrorCode.HOST_KEY_INVALID, () -> service.end(ROOM, new EndMeetingRequest("wrong")));
        assertDoesNotThrow(() -> service.end(ROOM, new EndMeetingRequest(KEY)));
        assertError(ApiErrorCode.MEETING_CLOSED, () -> service.saveBanner(ROOM, banner(KEY)));
        assertFalse(service.isBannerWritable(ROOM, KEY));
        verifyNoInteractions(banners);
    }

    @Test
    void expiredMeetingsRejectBannerAndLogoWrites() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1));
        assertError(ApiErrorCode.MEETING_EXPIRED, () -> service.saveBanner(ROOM, banner(KEY)));
        assertFalse(service.isBannerWritable(ROOM, KEY));
        verifyNoInteractions(banners);
    }

    @Test
    void validKeyAllowsActiveMeetingManagement() {
        Meeting meeting = givenMeeting(Meeting.STATUS_RUNNING,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertTrue(service.isBannerWritable(ROOM, KEY));
        service.saveBanner(ROOM, banner(KEY));
        verify(banners).insert(any(com.jitsimeet.api.domain.MeetingBanner.class));
        service.end(ROOM, new EndMeetingRequest(KEY));
        assertEquals(Meeting.STATUS_ENDED, meeting.getStatus());
        verify(mapper).updateStatusAuthorized(ROOM, KEY, Meeting.STATUS_RUNNING, Meeting.STATUS_ENDED);
    }

    @Test
    void cannotSilentlyReportUnstartedMeetingAsEnded() {
        givenMeeting(Meeting.STATUS_SCHEDULED, Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600));
        assertError(ApiErrorCode.INVALID_PARAM, () -> service.end(ROOM, new EndMeetingRequest(KEY)));
        verify(mapper, never()).updateById(any(Meeting.class));
    }

    @Test
    void initialBannerUsesMeetingSubjectWithoutSeparateBannerRecord() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        var response = service.getBanner(ROOM);
        assertNotNull(response);
        assertEquals("会议", response.text());
        assertTrue(response.visible());
    }

    @Test
    void savingBannerUpdatesCanonicalMeetingSubject() {
        Meeting meeting = givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        service.saveBanner(ROOM, new MeetingBannerRequest(KEY, true, "新主题", 32, "#FFFFFF", "", "", "", 2L));
        assertEquals("新主题", meeting.getSubject());
        verify(mapper).updateSubjectAuthorized(ROOM, KEY, Meeting.STATUS_RUNNING, "新主题");
    }

    @Test
    void legacyBannerTextNeverOverridesMeetingSubject() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        when(banners.selectById(ROOM)).thenReturn(new com.jitsimeet.api.domain.MeetingBanner(
                ROOM, false, "旧个人内容", 32, "#FFFFFF", "", "", "", 3L));
        var response = service.getBanner(ROOM);
        assertEquals("会议", response.text());
        assertFalse(response.visible());
    }

    @Test
    void hiddenBannerRetainsSubjectAndBlankSubjectCannotEraseIt() {
        Meeting meeting = givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        service.saveBanner(ROOM, new MeetingBannerRequest(KEY, false, "会议", 32, "#FFFFFF", "", "", "", 4L));
        assertEquals("会议", meeting.getSubject());
        clearInvocations(mapper, banners);
        assertError(ApiErrorCode.INVALID_PARAM, () -> service.saveBanner(ROOM,
                new MeetingBannerRequest(KEY, false, " ", 32, "#FFFFFF", "", "", "", 5L)));
        verify(mapper, never()).updateById(any(Meeting.class));
        verifyNoInteractions(banners);
    }

    private Meeting givenMeeting(String status, Instant start, Instant end) {
        Meeting meeting = new Meeting(ROOM, "会议", "", "主持人", KEY, "creator", start, end, status);
        when(mapper.findByRoomName(ROOM)).thenReturn(Optional.of(meeting));
        when(mapper.updateSubjectAuthorized(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.updateStatusAuthorized(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        return meeting;
    }

    @Test
    void saveReturnsServerVersionAndIgnoresClientClock() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        var result = service.saveBanner(ROOM, new MeetingBannerRequest(KEY, true, "新主题", 32,
                "#FFFFFF", "", "", "", Long.MAX_VALUE));
        assertEquals("新主题", result.text());
        assertTrue(result.updatedAt() > 0);
        assertTrue(result.updatedAt() < Long.MAX_VALUE);
    }

    @Test
    void consecutiveSavesHaveIncreasingServerVersions() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        var previous = new com.jitsimeet.api.domain.MeetingBanner(ROOM, true, "旧主题", 32,
                "#FFFFFF", "", "", "", System.currentTimeMillis() + 1000);
        when(banners.selectById(ROOM)).thenReturn(previous);
        var result = service.saveBanner(ROOM, banner(KEY));
        assertTrue(result.updatedAt() > previous.getUpdatedAt());
    }

    private MeetingBannerRequest banner(String key) {
        return new MeetingBannerRequest(key, true, "会议", 32, "#FFFFFF", "", "", "", 1L);
    }

    @Test
    void rejectedDatabaseWriteMustNotSaveBannerOrReportEndSuccess() {
        givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        when(mapper.updateSubjectAuthorized(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(mapper.updateStatusAuthorized(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        // A concurrent authority change can make the final conditional UPDATE affect zero rows.
        assertError(ApiErrorCode.HOST_KEY_INVALID, () -> service.saveBanner(ROOM, banner(KEY)));
        verifyNoInteractions(banners);
        assertError(ApiErrorCode.HOST_KEY_INVALID, () -> service.end(ROOM, new EndMeetingRequest(KEY)));
    }

    @Test
    void historicalLongSubjectCanBeHiddenButNotSilentlyReplacedWithAnotherLongSubject() {
        var meeting = givenMeeting(Meeting.STATUS_RUNNING, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        String historical = "会".repeat(120);
        meeting.updateSubject(historical);
        var saved = service.saveBanner(ROOM, new MeetingBannerRequest(KEY, false, historical, 32,
                "#FFFFFF", "", "", "", 0L));
        assertEquals(historical, saved.text());
        assertFalse(saved.visible());
        assertError(ApiErrorCode.INVALID_PARAM, () -> service.saveBanner(ROOM,
                new MeetingBannerRequest(KEY, true, "新".repeat(120), 32, "#FFFFFF", "", "", "", 0L)));
        assertEquals(historical, meeting.getSubject());
    }

    private void assertError(ApiErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected.getCode(), assertThrows(ApiException.class, action).getCode());
    }
}
