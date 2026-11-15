package com.exam.platform.modules.auth.dto;

/** 登录结果：短期 access token + 用户信息。 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserView user
) {
}
