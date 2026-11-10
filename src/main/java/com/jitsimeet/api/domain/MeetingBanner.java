package com.jitsimeet.api.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会议电子屏状态，按会议号持久化，供迟到入会者拉取。
 */
@TableName("meeting_banner")
public class MeetingBanner {

    @TableId(type = IdType.INPUT)
    private String roomName;

    private Boolean visible = false;

    private String text = "";

    private Integer fontSize = 32;

    private String color = "#FFFFFF";

    private String fontFamily = "";

    /** 背景色，空串表示无色（透明）。 */
    private String bgColor = "";

    /** 会标图片地址（相对路径，空串表示无会标）。 */
    private String logoUrl = "";

    /** 客户端毫秒时间戳，用于多端同步时防旧覆盖新。 */
    private Long updatedAt = 0L;

    protected MeetingBanner() {
    }

    public MeetingBanner(
        String roomName,
        Boolean visible,
        String text,
        Integer fontSize,
        String color,
        String fontFamily,
        String bgColor,
        String logoUrl,
        Long updatedAt
    ) {
        this.roomName = roomName;
        this.visible = visible;
        this.text = text;
        this.fontSize = fontSize;
        this.color = color;
        this.fontFamily = fontFamily;
        this.bgColor = bgColor;
        this.logoUrl = logoUrl;
        this.updatedAt = updatedAt;
    }

    public String getRoomName() {
        return roomName;
    }

    public Boolean getVisible() {
        return visible;
    }

    public String getText() {
        return text;
    }

    public Integer getFontSize() {
        return fontSize;
    }

    public String getColor() {
        return color;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public String getBgColor() {
        return bgColor;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }
}
