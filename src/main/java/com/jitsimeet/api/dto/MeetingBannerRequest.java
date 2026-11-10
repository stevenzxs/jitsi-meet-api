package com.jitsimeet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 保存会议电子屏请求，使用主持人密钥确认只有主持人可以修改电子屏。
 *
 * @param hostKey    主持人密钥
 * @param visible    是否展示
 * @param text       电子屏内容
 * @param fontSize   字号大小
 * @param color      文字颜色（#RRGGBB）
 * @param fontFamily 字体
 * @param bgColor    背景色（#RRGGBB，空串表示无色）
 * @param logoUrl    会标图片地址（空串表示无会标）
 * @param updatedAt  客户端毫秒时间戳
 */
public record MeetingBannerRequest(
    @NotBlank(message = "主持人密钥不能为空")
    String hostKey,

    @NotNull(message = "展示状态不能为空")
    Boolean visible,

    // Length is checked against the canonical subject in the service so historical
    // long subjects can retain their text when changing visibility/style.
    String text,

    @NotNull(message = "字号大小不能为空")
    @Min(value = 18, message = "字号大小不能小于 18")
    @Max(value = 160, message = "字号大小不能大于 160")
    Integer fontSize,

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "文字颜色格式不正确")
    String color,

    @Size(max = 100, message = "字体名称过长")
    String fontFamily,

    @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "背景色格式不正确")
    String bgColor,

    @Size(max = 300, message = "会标地址过长")
    @Pattern(regexp = "^$|^/cm/banner-logos/(\\d+|library)/[A-Za-z0-9._-]+$", message = "会标地址不正确")
    String logoUrl,

    @NotNull(message = "更新时间不能为空")
    @PositiveOrZero(message = "更新时间不能为负数")
    Long updatedAt
) {
}
