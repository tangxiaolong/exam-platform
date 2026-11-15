package com.exam.platform.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 小程序微信登录：code 为 wx.login 的返回值，绝不接受客户端传入 openid。 */
public record WechatLoginRequest(
        @NotBlank(message = "code 不能为空") String code,
        String nickname,
        String avatar
) {
}
