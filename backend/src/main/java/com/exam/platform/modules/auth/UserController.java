package com.exam.platform.modules.auth;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.auth.dto.ProfileUpdateRequest;
import com.exam.platform.modules.auth.dto.UserView;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.system.support.UserViews;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录用户资料。 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserMapper userMapper;

    @GetMapping("/me")
    public Result<UserView> me() {
        SysUser user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw BizException.notFound("用户");
        }
        return Result.ok(UserViews.of(user));
    }

    @PutMapping("/me")
    public Result<UserView> updateProfile(@RequestBody ProfileUpdateRequest request) {
        SysUser user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw BizException.notFound("用户");
        }
        if (request.nickname() != null && !request.nickname().isBlank()) {
            user.setNickname(request.nickname());
        }
        if (request.avatar() != null) {
            user.setAvatar(request.avatar());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        userMapper.updateById(user);
        return Result.ok(UserViews.of(user));
    }
}
