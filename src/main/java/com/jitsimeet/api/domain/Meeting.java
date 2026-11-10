package com.jitsimeet.api.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 会议记录，承载桌面端首页展示、预约和再次入会需要的数据。
 */
@TableName("meeting")
public class Meeting {
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.INPUT)
    private String id = UUID.randomUUID().toString();

    private String roomName;

    private String subject;

    private String password;

    private String displayName;

    private String hostKey;

    private String creatorId;

    private Instant scheduledStartTime;

    private Instant scheduledEndTime;

    private Instant startedAt;

    private Instant endedAt;

    private String status;

    private String joinUrl = "";

    private Instant createdAt = Instant.now();

    protected Meeting() {
    }

    /**
     * 创建会议记录时固化所有前端需要回显的字段，避免多入口重复推算。
     *
     * @param roomName 会议号
     * @param subject 会议主题
     * @param password 会议密码
     * @param displayName 主持人昵称
     * @param hostKey 主持人密钥
     * @param creatorId 创建人用户 ID
     * @param scheduledStartTime 计划开始时间
     * @param scheduledEndTime 计划结束时间
     * @param status 会议状态
     */
    public Meeting(
        String roomName,
        String subject,
        String password,
        String displayName,
        String hostKey,
        String creatorId,
        Instant scheduledStartTime,
        Instant scheduledEndTime,
        String status
    ) {
        this.roomName = roomName;
        this.subject = subject;
        this.password = password;
        this.displayName = displayName;
        this.hostKey = hostKey;
        this.creatorId = creatorId;
        this.scheduledStartTime = scheduledStartTime;
        this.scheduledEndTime = scheduledEndTime;
        this.status = status;
        this.startedAt = STATUS_RUNNING.equals(status) ? Instant.now() : null;
    }

    /**
     * 取消会议时保留记录，但从桌面可见列表中移除。
     */
    public void cancel() {
        this.status = STATUS_CANCELLED;
        this.endedAt = Instant.now();
    }

    /**
     * 参会人真正进入 Jitsi 房间后，把预定会议切换为进行中。
     */
    public void start() {
        this.status = STATUS_RUNNING;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    /**
     * 会议被明确结束或最后一个参会人离开时，保留历史记录并禁止后续再次入会。
     */
    public void end() {
        this.status = STATUS_ENDED;
        this.endedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getSubject() {
        return subject;
    }

    public void updateSubject(String subject) {
        this.subject = subject;
    }

    public String getPassword() {
        return password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHostKey() {
        return hostKey;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public Instant getScheduledStartTime() {
        return scheduledStartTime;
    }

    public Instant getScheduledEndTime() {
        return scheduledEndTime;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
