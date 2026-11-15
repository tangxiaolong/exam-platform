package com.exam.platform.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理后台账号密码登录。
 *
 * @param tenantCode 租户编码，缺省为平台租户 PLATFORM；账号名只在租户内唯一，
 *                   因此多租户部署时必须显式指定，避免跨租户撞名
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String tenantCode
) {
}
