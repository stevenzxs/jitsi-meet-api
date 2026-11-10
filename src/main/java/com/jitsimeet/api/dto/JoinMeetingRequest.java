package com.jitsimeet.api.dto;

/**
 * 桌面端加入会议前的校验请求。
 *
 * @param password 用户输入的会议密码；无密码会议允许为空
 */
public record JoinMeetingRequest(
    String password
) {
}
