package com.jitsimeet.api.dto;

/**
 * 会议电子屏状态响应。
 *
 * @param visible    是否展示
 * @param text       电子屏内容
 * @param fontSize   字号大小
 * @param color      文字颜色（#RRGGBB）
 * @param fontFamily 字体
 * @param bgColor    背景色（#RRGGBB，空串表示无色）
 * @param logoUrl    会标图片地址（空串表示无会标）
 * @param updatedAt  客户端毫秒时间戳
 */
public record MeetingBannerResponse(
    boolean visible,
    String text,
    int fontSize,
    String color,
    String fontFamily,
    String bgColor,
    String logoUrl,
    long updatedAt
) {
}
