package com.exam.platform.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 小程序微信登录：code 为 wx.login 的返回值，绝不接受客户端传入 openid。
 *
 * <p>{@code tenantCode} 决定微信用户归到哪个租户，缺省时落到平台租户——
 * 与密码登录的 {@code applyTenant} 保持同一套语义。没有它时，所有机构的微信考生
 * 都会被静默归到平台租户：单租户部署下看不出问题，一旦某机构用自己的小程序接入，
 * 它的考生会写进平台租户的数据里，且事后极难拆分。</p>
 */
public record WechatLoginRequest(
        @NotBlank(message = "code 不能为空") String code,
        String nickname,
        String avatar,
        String tenantCode
) {
}
