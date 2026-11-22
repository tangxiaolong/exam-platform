package com.exam.platform.modules.system;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 角色与权限管理（平台后台）。
 *
 * <p>{@code /v1/admin/**} 已由 {@code AuthInterceptor} 强制平台管理员，
 * 这里不再重复判断角色。真正需要细分权限的接口应调用
 * {@link UserContext#requirePermission(String)}，而不是继续加角色判断。</p>
 *
 * <p>所有写接口都标 {@code @Audited}：权限变更必须能回答「谁在什么时候给了谁什么」，
 * 这是事后追查越权的唯一线索。</p>
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> listRoles() {
        return Result.ok(roleService.listRoles());
    }

    @GetMapping("/roles/{code}/permissions")
    public Result<List<Map<String, Object>>> rolePermissions(@PathVariable String code) {
        return Result.ok(roleService.permissionsOfRole(code));
    }

    @DeleteMapping("/roles/{code}")
    @Audited(action = "ROLE_DELETE", target = "'sys_role:' + #code")
    public Result<Void> deleteRole(@PathVariable String code) {
        // 删角色会连带影响持有它的用户，属于后台最危险的动作之一：
        // 角色层只能回答「是不是平台管理员」，回答不了「这个管理员能不能删角色」
        UserContext.requirePermission("role:delete");
        roleService.deleteRole(code);
        return Result.ok(null);
    }

    @PostMapping("/roles")
    @Audited(action = "ROLE_CREATE", target = "'sys_role:' + #p0['code']", detail = "#p0['name']")
    public Result<Void> createRole(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        roleService.createRole(code, body == null ? null : body.get("name"),
                body == null ? null : body.get("description"));
        return Result.ok(null);
    }

    @PostMapping("/roles/{code}/permissions")
    @Audited(action = "ROLE_PERMISSION_GRANT", target = "'sys_role:' + #code", detail = "#permission")
    public Result<Void> grantPermission(@PathVariable String code,
                                        @RequestParam String permission) {
        roleService.grantPermission(code, permission);
        return Result.ok(null);
    }

    @DeleteMapping("/roles/{code}/permissions")
    @Audited(action = "ROLE_PERMISSION_REVOKE", target = "'sys_role:' + #code", detail = "#permission")
    public Result<Void> revokePermission(@PathVariable String code,
                                         @RequestParam String permission) {
        roleService.revokePermission(code, permission);
        return Result.ok(null);
    }

    @GetMapping("/users/{id}/roles")
    public Result<List<String>> userRoles(@PathVariable Long id) {
        return Result.ok(List.copyOf(roleService.rolesOf(id)));
    }

    @PostMapping("/users/{id}/roles")
    @Audited(action = "USER_ROLE_GRANT", target = "'sys_user:' + #id", detail = "#roleCode")
    public Result<Void> grantRole(@PathVariable Long id, @RequestParam String roleCode) {
        roleService.grant(id, roleCode);
        return Result.ok(null);
    }

    @DeleteMapping("/users/{id}/roles/{code}")
    @Audited(action = "USER_ROLE_REVOKE", target = "'sys_user:' + #id", detail = "#code")
    public Result<Void> revokeRole(@PathVariable Long id, @PathVariable String code) {
        if (id != null && id == UserContext.userId()) {
            // 自己撤自己的管理员权限会让当前会话在下一个请求就失去后台入口，
            // 出问题时连补救的账号都没有——要求由另一个管理员操作
            throw new BizException("AUTH_SELF_REVOKE", "不能撤销自己的角色，请由其他管理员操作");
        }
        roleService.revoke(id, code);
        return Result.ok(null);
    }
}
