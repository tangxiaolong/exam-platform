package com.exam.platform.modules.auth.dto;

/** 更新个人资料。 */
public record ProfileUpdateRequest(String nickname, String avatar, String phone) {
}
