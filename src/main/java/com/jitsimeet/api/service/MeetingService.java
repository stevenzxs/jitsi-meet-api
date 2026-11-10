package com.jitsimeet.api.service;

import com.jitsimeet.api.domain.Meeting;
import com.jitsimeet.api.domain.MeetingBanner;
import com.jitsimeet.api.dto.*;
import com.jitsimeet.api.exception.ApiErrorCode;
import com.jitsimeet.api.exception.ApiException;
import com.jitsimeet.api.mapper.MeetingBannerMapper;
import com.jitsimeet.api.mapper.MeetingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 会议业务服务，负责把桌面端会议数据持久化到 SQLite。
 */
@Service
public class MeetingService {
    private final MeetingMapper meetingMapper;
    private final MeetingBannerMapper meetingBannerMapper;
    private final JitsiTokenService jitsiTokenService;
    private final CreatorRecoveryService creatorRecoveryService;
    private final HostAuthorityFence hostAuthorityFence;

    /**
     * 注入会议 Mapper。
     *
     * @param meetingMapper       会议 Mapper
     * @param meetingBannerMapper 会议电子屏 Mapper
     * @param jitsiTokenService   Jitsi JWT 签发服务
     */
    public MeetingService(MeetingMapper meetingMapper, MeetingBannerMapper meetingBannerMapper, JitsiTokenService jitsiTokenService,
                          CreatorRecoveryService creatorRecoveryService, HostAuthorityFence hostAuthorityFence) {
        this.meetingMapper = meetingMapper;
        this.meetingBannerMapper = meetingBannerMapper;
        this.jitsiTokenService = jitsiTokenService;
        this.creatorRecoveryService = creatorRecoveryService;
        this.hostAuthorityFence = hostAuthorityFence;
    }

    /**
     * 查询桌面端首页可见会议：仅返回当前用户创建的、尚未开始的预定会议。
     * 快速会议不落列表，取消/结束的会议自动隐藏。
     *
     * @param creatorId 创建人用户 ID
     * @return 会议列表
     */
    @Transactional(readOnly = true)
    public List<MeetingResponse> listVisible(String creatorId) {
        if (creatorId == null || creatorId.isBlank()) {
            return List.of();
        }
        return meetingMapper.listVisible(creatorId).stream().map(this::toResponse).toList();
    }

    /**
     * 查询桌面端历史会议：保留当前用户创建过的所有会议记录，用于桌面首页历史列表。
     *
     * @param creatorId 创建人用户 ID
     * @return 历史会议列表
     */
    @Transactional(readOnly = true)
    public List<MeetingResponse> listHistory(String creatorId) {
        if (creatorId == null || creatorId.isBlank()) {
            return List.of();
        }
        return meetingMapper.listHistory(creatorId).stream()
                // 历史列表只展示已经结束语义的记录，进行中和未开始的预定会议仍留在其它入口。
                .filter(meeting -> isClosedStatus(meeting) || isExpired(meeting))
                .map(this::toResponse)
                .toList();
    }

    /**
     * 创建即时会议记录。
     *
     * @param request 创建请求
     * @return 会议响应
     */
    @Transactional
    public MeetingResponse createInstant(MeetingRequest request) {
        Instant start = Instant.now();
        Meeting meeting = buildMeeting(request, start, Meeting.STATUS_RUNNING);
        meetingMapper.insert(meeting);
        creatorRecoveryService.register(meeting.getRoomName(), request.creatorRecoveryKey());
        return toResponse(meeting);
    }

    /**
     * 创建预约会议记录。
     *
     * @param request 预约请求
     * @return 会议响应
     */
    @Transactional
    public MeetingResponse schedule(MeetingRequest request) {
        if (request.scheduledStartTime() == null || request.scheduledStartTime().isBefore(Instant.now())) {
            throw new ApiException(ApiErrorCode.INVALID_PARAM, "预约开始时间必须晚于当前时间");
        }
        Meeting meeting = buildMeeting(request, request.scheduledStartTime(), Meeting.STATUS_SCHEDULED);
        meetingMapper.insert(meeting);
        creatorRecoveryService.register(meeting.getRoomName(), request.creatorRecoveryKey());
        return toResponse(meeting);
    }

    /**
     * 持有会议管理密钥者可取消会议，保留历史记录但从首页列表隐藏。
     *
     * @param roomName  会议号
     * @param hostKey 主持人密钥，不以客户端 creatorId 作为授权凭证
     */
    @Transactional
    public void cancel(String roomName, String hostKey) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertHostKey(meeting, hostKey);
        if (isClosedStatus(meeting)) {
            return;
        }
        requireAuthorizedWrite(meetingMapper.updateStatusAuthorized(roomName, meeting.getHostKey(),
                meeting.getStatus(), Meeting.STATUS_CANCELLED));
        meeting.cancel();
    }

    /**
     * 删除历史会议记录，只允许删除已经结束、取消或过期的会议，避免误删仍可进入的会议。
     *
     * @param roomName  会议号
     * @param hostKey 主持人密钥
     */
    @Transactional
    public void deleteHistory(String roomName, String hostKey) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertHostKey(meeting, hostKey);
        if (!isClosedStatus(meeting) && !isExpired(meeting)) {
            throw new ApiException(ApiErrorCode.INVALID_PARAM, "只能删除已结束或已过期的历史会议");
        }
        meetingMapper.deleteById(meeting.getId());
        creatorRecoveryService.delete(roomName);
    }

    /**
     * 明确结束会议，结束后立刻进入历史列表，并禁止后续再次入会。
     *
     * @param roomName 会议号
     * @param request  结束会议请求
     */
    @Transactional
    public void end(String roomName, EndMeetingRequest request) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertHostKey(meeting, request == null ? null : request.hostKey());
        if (isClosedStatus(meeting)) {
            return;
        }
        //如果还没到开始时间
        if (isUnStart(meeting)) {
            throw new ApiException(ApiErrorCode.INVALID_PARAM, "会议尚未开始，请使用取消预定功能");
        }
        // 即时会议和预定会议都统一落 ENDED，历史查询就不需要等待计划结束时间自然过期。
        requireAuthorizedWrite(meetingMapper.updateStatusAuthorized(roomName, meeting.getHostKey(),
                meeting.getStatus(), Meeting.STATUS_ENDED));
        meeting.end();
    }

    /**
     * 记录参会人已真实进入 Jitsi 房间，用于把预定会议从未开始切换为进行中。
     *
     * @param roomName 会议号
     * @param request  参会人入会请求
     */
    @Transactional
    public void participantJoined(String roomName, ParticipantPresenceRequest request) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertJoinable(meeting);
        if (Meeting.STATUS_SCHEDULED.equals(meeting.getStatus()) && !isUnStart(meeting)) {
            // 预定会议只有真正有人进入 Jitsi 后才从首页预定列表切换到进行中语义。
            meetingMapper.startScheduled(roomName);
        }
    }

    /**
     * 校验桌面端入会条件，避免不存在、已结束或密码错误的会议继续打开 Jitsi 窗口。
     *
     * @param roomName 会议号
     * @param request  入会校验请求
     * @return 可入会的安全会议信息
     */
    @Transactional(readOnly = true)
    public JoinMeetingResponse joinCheck(String roomName, JoinMeetingRequest request) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertJoinable(meeting);
        validateMeetingPassword(meeting, request == null ? "" : request.password());
        return new JoinMeetingResponse(
                meeting.getRoomName(),
                meeting.getSubject(),
                meeting.getStatus(),
                meeting.getScheduledEndTime(),
                hasPassword(meeting)
        );
    }

    /**
     * 校验主持人密钥并签发 Jitsi 主持人 JWT。
     *
     * @param roomName 会议号
     * @param request  主持人令牌请求
     * @return 主持人 JWT
     */
    @Transactional(readOnly = true)
    public HostTokenResponse hostToken(String roomName, HostTokenRequest request) {
        hostAuthorityFence.requireActive(roomName);
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertHostKey(meeting, request == null ? null : request.hostKey());
        assertJoinable(meeting);
        return jitsiTokenService.issueHostToken(meeting, request.displayName());
    }

    /**
     * 读取会议电子屏状态，供参会人入会后拉取；未设置过返回 null。
     *
     * @param roomName 会议号
     * @return 电子屏状态，未设置时为 null
     */
    @Transactional(readOnly = true)
    public MeetingBannerResponse getBanner(String roomName) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        MeetingBanner banner = meetingBannerMapper.selectById(roomName);
        if (banner == null) {
            return new MeetingBannerResponse(true, meeting.getSubject(), 32, "#FFFFFF", "", "", "", 0L);
        }
        MeetingBannerResponse settings = toBannerResponse(banner);
        return new MeetingBannerResponse(settings.visible(), meeting.getSubject(), settings.fontSize(),
                settings.color(), settings.fontFamily(), settings.bgColor(), settings.logoUrl(), settings.updatedAt());
    }

    /**
     * 校验主持人是否有权上传/删除会标图片，供 nginx auth_request 调用，不抛异常。
     *
     * @param roomName 会议号
     * @param hostKey  主持人密钥
     * @return true 表示有权操作会标图片
     */
    @Transactional(readOnly = true)
    public boolean isBannerWritable(String roomName, String hostKey) {
        if (!hostAuthorityFence.isActive(roomName)) return false;
        return meetingMapper.findByRoomName(roomName)
                .map(meeting -> matchesHostKey(meeting, hostKey) && !isClosedStatus(meeting) && !isExpired(meeting))
                .orElse(false);
    }

    /**
     * 保存会议电子屏状态，仅主持人可写；同一会议重复保存时覆盖。
     *
     * @param roomName 会议号
     * @param request  电子屏保存请求
     */
    @Transactional
    public MeetingBannerResponse saveBanner(String roomName, MeetingBannerRequest request) {
        Meeting meeting = meetingMapper.findByRoomName(roomName)
                .orElseThrow(() -> new ApiException(ApiErrorCode.MEETING_NOT_FOUND));
        assertHostKey(meeting, request == null ? null : request.hostKey());
        assertJoinable(meeting);
        String subject = request.text() == null ? "" : request.text().trim();
        if (subject.isBlank() || (subject.length() > 80 && !subject.equals(meeting.getSubject()))) {
            throw new ApiException(ApiErrorCode.INVALID_PARAM);
        }
        requireAuthorizedWrite(meetingMapper.updateSubjectAuthorized(roomName, meeting.getHostKey(),
                meeting.getStatus(), subject));
        meeting.updateSubject(subject);
        MeetingBanner previous = meetingBannerMapper.selectById(roomName);
        long previousVersion = previous == null || previous.getUpdatedAt() == null ? 0L : previous.getUpdatedAt();
        // Client clocks are not an ordering authority. Repair legacy future timestamps too.
        long now = System.currentTimeMillis();
        long version = previousVersion > now + 60_000 || previousVersion == Long.MAX_VALUE
                ? now : Math.max(now, previousVersion + 1);
        MeetingBanner banner = new MeetingBanner(
                roomName,
                request.visible(),
                request.text() == null ? "" : request.text().trim(),
                request.fontSize(),
                request.color() == null ? "#FFFFFF" : request.color(),
                request.fontFamily() == null ? "" : request.fontFamily(),
                request.bgColor() == null ? "" : request.bgColor(),
                request.logoUrl() == null ? "" : request.logoUrl(),
                version
        );
        if (previous == null) {
            meetingBannerMapper.insert(banner);
        } else {
            meetingBannerMapper.updateById(banner);
        }
        return toBannerResponse(banner);
    }

    private void requireAuthorizedWrite(int affected) {
        if (affected != 1) throw new ApiException(ApiErrorCode.HOST_KEY_INVALID);
    }

    private Meeting buildMeeting(MeetingRequest request, Instant start, String status) {
        meetingMapper.findByRoomName(request.roomName()).ifPresent(existing -> {
            throw new ApiException(ApiErrorCode.MEETING_DUPLICATED);
        });
        int durationMinutes = request.durationMinutes() == null ? 60 : request.durationMinutes();
        Instant end = start.plus(durationMinutes, ChronoUnit.MINUTES);
        // 凭据供服务端校验；客户端在创建时自行保存，不通过读取接口回显。
        return new Meeting(
                request.roomName(),
                request.subject().trim(),
                normalize(request.password()),
                normalize(request.displayName()),
                normalize(request.hostKey()),
                normalize(request.creatorId()),
                start,
                end,
                status
        );
    }

    private MeetingResponse toResponse(Meeting meeting) {
        return new MeetingResponse(
                meeting.getId(),
                meeting.getRoomName(),
                meeting.getSubject(),
                meeting.getStatus(),
                meeting.getScheduledStartTime(),
                meeting.getScheduledEndTime(),
                meeting.getStartedAt(),
                meeting.getEndedAt(),
                null,
                meeting.getDisplayName(),
                null,
                meeting.getCreatorId()
        );
    }

    private MeetingBannerResponse toBannerResponse(MeetingBanner banner) {
        return new MeetingBannerResponse(
                Boolean.TRUE.equals(banner.getVisible()),
                banner.getText(),
                banner.getFontSize() == null ? 32 : banner.getFontSize(),
                banner.getColor(),
                banner.getFontFamily(),
                banner.getBgColor(),
                banner.getLogoUrl(),
                banner.getUpdatedAt() == null ? 0L : banner.getUpdatedAt()
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void assertHostKey(Meeting meeting, String hostKey) {
        if (!matchesHostKey(meeting, hostKey)) {
            throw new ApiException(ApiErrorCode.HOST_KEY_INVALID);
        }
    }

    private boolean matchesHostKey(Meeting meeting, String hostKey) {
        String input = normalize(hostKey);
        String expected = meeting.getHostKey();
        return input != null && expected != null && !expected.isBlank()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断会议是否处于不可入会状态，状态关闭或结束时间已过都视为不可进入。
     *
     * @param meeting 会议记录
     */
    private void assertJoinable(Meeting meeting) {
        if (isClosedStatus(meeting)) {
            throw new ApiException(ApiErrorCode.MEETING_CLOSED);
        }
        if (isExpired(meeting)) {
            throw new ApiException(ApiErrorCode.MEETING_EXPIRED);
        }
    }

    /**
     * 校验入会密码；有密码会议必须完全匹配，空密码会议不拦截。
     *
     * @param meeting  会议记录
     * @param password 用户输入密码
     */
    private void validateMeetingPassword(Meeting meeting, String password) {
        if (!hasPassword(meeting)) {
            return;
        }
        String input = normalize(password);
        if (input == null) {
            throw new ApiException(ApiErrorCode.MEETING_PASSWORD_REQUIRED);
        }
        if (!meeting.getPassword().equals(input)) {
            throw new ApiException(ApiErrorCode.MEETING_PASSWORD_INVALID);
        }
    }

    /**
     * 判断会议是否配置了入会密码。
     *
     * @param meeting 会议记录
     * @return true 表示需要密码
     */
    private boolean hasPassword(Meeting meeting) {
        return meeting.getPassword() != null && !meeting.getPassword().isBlank();
    }

    /**
     * 判断会议是否已经到达结束时间。
     *
     * @param meeting 会议记录
     * @return true 表示会议已过期
     */
    private boolean isExpired(Meeting meeting) {
        return meeting.getScheduledEndTime() != null && !meeting.getScheduledEndTime().isAfter(Instant.now());
    }

    /**
     * 判断会议状态是否已经关闭。
     *
     * @param meeting 会议记录
     * @return true 表示会议已取消或已结束
     */
    private boolean isClosedStatus(Meeting meeting) {
        return Meeting.STATUS_CANCELLED.equals(meeting.getStatus()) || Meeting.STATUS_ENDED.equals(meeting.getStatus());
    }

    /**
     * 预约会议未开始
     *
     * @param meeting
     * @return
     */
    private boolean isUnStart(Meeting meeting) {
        return meeting.getScheduledStartTime().isAfter(Instant.now());
    }
}
