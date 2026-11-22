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
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    /** 只用于写 token_invalid_before：实体不加这个字段，避免它被顺手带进资料更新。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    /**
     * 修改密码：校验原密码 → 更新哈希 → 让此用户已签发的全部 token 失效。
     *
     * <p>「改密码」在安全语义上不只是换一个哈希，而是**让别处的登录态作废**。
     * 如果只更新密码，攻击者用旧 token 仍能继续操作直到过期，改密码就失去了止血作用。</p>
     */
    @PutMapping("/me/password")
    public Result<Void> changePassword(@RequestBody java.util.Map<String, String> body) {
        SysUser user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw BizException.notFound("用户");
        }
        String oldPassword = body == null ? null : body.get("oldPassword");
        String newPassword = body == null ? null : body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) {
            throw BizException.badRequest("请输入原密码");
        }
        if (newPassword == null || newPassword.trim().length() < 8) {
            throw BizException.badRequest("新密码至少 8 位");
        }
        if (newPassword.trim().equals(oldPassword)) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        // 微信注册用户没有密码：这种情况不允许走「改密码」入口，
        // 否则任何持有一个有效 token 的人都能给无密码账号设一个密码
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw BizException.badRequest("当前账号未设置密码，请使用其他方式登录后设置");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw BizException.badRequest("原密码不正确");
        }
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
        // 保留毫秒：token 里带 iatMs（毫秒级签发时间），用秒级时间会把
        // 「本秒内重新登录拿到的新 token」和旧 token 一起作废
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()),
                user.getId());
        return Result.ok(null);
    }

    /**
     * 为无密码账号设置初始密码（微信登录注册的用户）。
     *
     * <p>与「修改密码」分开是必要的：这类账号没有原密码可校验，
     * 硬塞进同一个接口只会让「原密码是否必填」变得含混，最终演变成
     * 「原密码为空就跳过校验」——那等于给所有账号开了一个绕过口。</p>
     *
     * <p>设置后**不作废当前 token**：这一步是本人主动发起，踢掉自己只会让流程更差；
     * 真正需要作废的是「别人持有旧 token」的场景，那由修改密码负责。</p>
     */
    @org.springframework.web.bind.annotation.PostMapping("/me/password/setup")
    public Result<Void> setupPassword(@RequestBody java.util.Map<String, String> body) {
        SysUser user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw BizException.notFound("用户");
        }
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            throw BizException.badRequest("账号已有密码，请使用修改密码");
        }
        String newPassword = body == null ? null : body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 8) {
            throw BizException.badRequest("密码至少 8 位");
        }
        // 微信注册的账号名是 "wx_" + openid 片段，考生不可能知道，
        // 光设密码等于设了一个用不上的密码——所以这里必须一并给出可登录的账号名
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            String trimmed = username.trim();
            if (!trimmed.matches("[a-zA-Z0-9_]{4,32}")) {
                throw BizException.badRequest("账号名需 4-32 位字母、数字或下划线");
            }
            Integer taken = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE tenant_id = ? AND username = ? "
                            + "AND deleted = 0 AND id <> ?",
                    Integer.class, UserContext.tenantId(), trimmed, user.getId());
            if (taken != null && taken > 0) {
                throw BizException.badRequest("账号名已被占用：" + trimmed);
            }
            user.setUsername(trimmed);
        } else if (user.getUsername() != null && user.getUsername().startsWith("wx_")) {
            throw BizException.badRequest("请同时设置登录账号名，否则设置密码后用不上");
        }
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
        return Result.ok(null);
    }
}
