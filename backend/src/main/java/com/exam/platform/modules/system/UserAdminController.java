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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营后台：用户管理。 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final SysUserMapper userMapper;

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

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户");
        }
        return user;
    }
}
