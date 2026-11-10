package com.jitsimeet.api.controller;

import com.jitsimeet.api.dto.HostTokenRequest;
import com.jitsimeet.api.dto.HostTokenResponse;
import com.jitsimeet.api.dto.EndMeetingRequest;
import com.jitsimeet.api.dto.JoinMeetingRequest;
import com.jitsimeet.api.dto.JoinMeetingResponse;
import com.jitsimeet.api.dto.MeetingBannerRequest;
import com.jitsimeet.api.dto.MeetingBannerResponse;
import com.jitsimeet.api.dto.MeetingRequest;
import com.jitsimeet.api.dto.MeetingResponse;
import com.jitsimeet.api.dto.ParticipantPresenceRequest;
import com.jitsimeet.api.service.MeetingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 桌面端会议数据接口，最终访问路径由 server.servlet.context-path 统一增加 API 前缀。
 */
@Validated
@RestController
@RequestMapping("/open/desktop/meetings")
public class MeetingController {
    private final MeetingService meetingService;

    /**
     * 注入会议服务。
     *
     * @param meetingService 会议服务
     */
    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    /**
     * 查询桌面端会议列表，只返回当前用户创建的预定会议。
     *
     * @param creatorId 创建人用户 ID
     * @return 会议列表
     */
    @GetMapping
    public List<MeetingResponse> list(@RequestParam(required = false) String creatorId) {
        return meetingService.listVisible(creatorId);
    }

    /**
     * 查询桌面端历史会议列表，包含当前用户创建过的快速会议和预定会议。
     *
     * @param creatorId 创建人用户 ID
     * @return 历史会议列表
     */
    @GetMapping("/history")
    public List<MeetingResponse> history(@RequestParam(required = false) String creatorId) {
        return meetingService.listHistory(creatorId);
    }

    /**
     * 创建即时会议记录。
     *
     * @param request 创建请求
     * @return 会议响应
     */
    @PostMapping
    public MeetingResponse create(@Valid @RequestBody MeetingRequest request) {
        return meetingService.createInstant(request);
    }

    /**
     * 预定会议。
     *
     * @param request 预定请求
     * @return 会议响应
     */
    @PostMapping("/schedule")
    public MeetingResponse schedule(@Valid @RequestBody MeetingRequest request) {
        return meetingService.schedule(request);
    }

    /**
     * 取消会议。
     *
     * @param roomName 会议号
     * @param hostKey 主持人密钥
     */
    @DeleteMapping("/{roomName}")
    public void cancel(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @RequestHeader(value = "X-Host-Key", required = false) String hostKey
    ) {
        meetingService.cancel(roomName, hostKey);
    }

    /**
     * 删除历史会议记录，只影响历史列表展示，不用于取消仍可加入的会议。
     *
     * @param roomName 会议号
     * @param hostKey 主持人密钥
     */
    @DeleteMapping("/{roomName}/history")
    public void deleteHistory(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @RequestHeader(value = "X-Host-Key", required = false) String hostKey
    ) {
        meetingService.deleteHistory(roomName, hostKey);
    }

    /**
     * 主持人结束会议，结束后会议会进入历史列表且不能再次加入。
     *
     * @param roomName 会议号
     * @param request 结束会议请求
     */
    @PostMapping("/{roomName}/end")
    public void end(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @Valid @RequestBody EndMeetingRequest request
    ) {
        meetingService.end(roomName, request);
    }

    /**
     * 参会人进入 Jitsi 后上报入会事件，用于把预定会议切换为进行中。
     *
     * @param roomName 会议号
     * @param request 参会人入会请求
     */
    @PostMapping("/{roomName}/participants/join")
    public void participantJoined(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @Valid @RequestBody ParticipantPresenceRequest request
    ) {
        meetingService.participantJoined(roomName, request);
    }

    /**
     * 加入会议前校验会议是否存在、是否过期以及密码是否正确。
     *
     * @param roomName 会议号
     * @param request 入会校验请求
     * @return 可入会的会议信息
     */
    @PostMapping("/{roomName}/join-check")
    public JoinMeetingResponse joinCheck(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @RequestBody(required = false) JoinMeetingRequest request
    ) {
        return meetingService.joinCheck(roomName, request);
    }

    /**
     * 签发主持人 JWT，前端不再持有 Jitsi app_secret。
     *
     * @param roomName 会议号
     * @param request 令牌请求
     * @return 主持人令牌
     */
    @PostMapping("/{roomName}/host-token")
    public HostTokenResponse hostToken(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @Valid @RequestBody HostTokenRequest request
    ) {
        return meetingService.hostToken(roomName, request);
    }

    /**
     * 读取会议电子屏状态，参会人入会后调用；未设置过返回 204。
     *
     * @param roomName 会议号
     * @return 电子屏状态
     */
    @GetMapping("/{roomName}/banner")
    public ResponseEntity<MeetingBannerResponse> banner(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName
    ) {
        MeetingBannerResponse banner = meetingService.getBanner(roomName);
        return banner == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(banner);
    }

    /**
     * 保存会议电子屏状态，仅主持人（hostKey 校验）可写。
     *
     * @param roomName 会议号
     * @param request  电子屏保存请求
     */
    @PostMapping("/{roomName}/banner")
    public MeetingBannerResponse saveBanner(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @Valid @RequestBody MeetingBannerRequest request
    ) {
        return meetingService.saveBanner(roomName, request);
    }

    /** Transitional authorization route for older Web builds using DAV.
     * Remove after every gateway has switched to the API logo write route. */
    @GetMapping("/{roomName}/banner/logo-auth")
    public ResponseEntity<Void> bannerLogoAuth(
        @PathVariable @Pattern(regexp = "\\d{9}") String roomName,
        @RequestHeader(value = "X-Host-Key", required = false) String hostKey
    ) {
        return meetingService.isBannerWritable(roomName, hostKey)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

}
