package com.exam.platform.modules.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.auth.dto.UserView;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.modules.system.support.UserViews;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 运营后台：用户管理。 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public Result<PageResult<UserView>> page(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String roleCode,
                                             PageQuery pageQuery) {
        Page<SysUser> page = userMapper.selectPage(pageQuery.toPage(), Wrappers.<SysUser>lambdaQuery()
                .eq(roleCode != null && !roleCode.isBlank(), SysUser::getRoleCode, roleCode)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getNickname, keyword))
                .orderByDesc(SysUser::getId));
        return Result.ok(PageResult.of(page.getRecords().stream().map(UserViews::of).toList(),
                page.getTotal(), page.getCurrent(), page.getSize()));
    }

    @PutMapping("/{id}/status")
    @Audited(action = "USER_STATUS", target = "'sys_user:' + #id", detail = "#status")
    public Result<UserView> changeStatus(@PathVariable Long id, @RequestParam String status) {
        SysUser user = requireUser(id);
        user.setStatus(status);
        userMapper.updateById(user);
        return Result.ok(UserViews.of(user));
    }

    @PutMapping("/{id}/role")
    @Audited(action = "USER_ROLE", target = "'sys_user:' + #id", detail = "#roleCode")
    public Result<UserView> changeRole(@PathVariable Long id, @RequestParam String roleCode) {
        SysUser user = requireUser(id);
        user.setRoleCode(roleCode);
        userMapper.updateById(user);
        return Result.ok(UserViews.of(user));
    }

    /**
     * 强制下线：把 {@code token_invalid_before} 推到当前时刻，此前签发的 token 立即失效。
     *
     * <p>改密码走的是同一条路径，但这里不碰密码——员工离职、设备丢失这类场景，
     * 需要的是「让已有会话立刻作废」，而不是又给对方一个新密码。</p>
     *
     * <p>只写这一个字段、不走 {@code updateById}：实体刻意不带 tokenInvalidBefore，
     * 避免它被资料更新顺手覆盖成 null，那等于给所有人续命。</p>
     */
    @PostMapping("/{id}/sessions/revoke")
    @Audited(action = "USER_SESSIONS_REVOKE", target = "'sys_user:' + #id")
    public Result<Void> revokeSessions(@PathVariable Long id) {
        requireUser(id);
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = NOW(), updated_at = NOW() "
                + "WHERE id = ?", id);
        return Result.ok(null);
    }

    /**
     * 管理员重置他人密码，**同时作废该用户已有会话**。
     *
     * <p>两件事必须一起做：只改哈希的话，对方手里那个旧 token 仍能继续操作到过期，
     * 管理员以为「重置」了，实际只是换了一把没人用的钥匙。</p>
     *
     * <p>与本人改密的区别是不校验原密码——这正是管理员帮忙的场景，
     * 但因此要求调用方必须是平台管理员（由 {@code /v1/admin/**} 拦截器保证）。</p>
     */
    @PutMapping("/{id}/password")
    @Audited(action = "USER_PASSWORD_RESET", target = "'sys_user:' + #id")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        SysUser user = requireUser(id);
        String newPassword = body == null ? null : body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 8) {
            throw BizException.badRequest("新密码至少 8 位");
        }
        // 微信注册用户没有密码：直接报错，别给一个用不上的密码
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw BizException.badRequest("该账号尚未设置密码，请让用户先在「设置登录密码」里自行设置");
        }
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = NOW(), updated_at = NOW() "
                + "WHERE id = ?", id);
        return Result.ok(null);
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户");
        }
        return user;
    }
}
