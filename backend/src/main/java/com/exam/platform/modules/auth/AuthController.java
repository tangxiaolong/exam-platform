package com.exam.platform.modules.auth;

import com.exam.platform.common.api.Result;
import com.exam.platform.modules.auth.dto.LoginRequest;
import com.exam.platform.modules.auth.dto.LoginResponse;
import com.exam.platform.modules.auth.dto.WechatLoginRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口（免登录白名单）。 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 管理后台账号密码登录。 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.loginByPassword(request));
    }

    /** 小程序微信登录。 */
    @PostMapping("/wechat-login")
    public Result<LoginResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        return Result.ok(authService.loginByWechat(request));
    }
}
