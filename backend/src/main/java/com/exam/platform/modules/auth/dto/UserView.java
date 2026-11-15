package com.exam.platform.modules.auth.dto;

/** 当前用户信息。 */
public record UserView(
        Long id,
        String username,
        String nickname,
        String avatar,
        String phone,
        String roleCode,
        String status,
        Long tenantId
) {
}
