package com.exam.platform.modules.system;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色三元组（角色 / 用户-角色 / 角色-权限）。
 *
 * <p>为什么这里用 JdbcTemplate 而不是 MyBatis-Plus 实体：RBAC 是横切能力，
 * 被鉴权入口每个请求调用一次。SQL 是固定的三条单表查询，用显式 {@code tenant_id}
 * 反而比依赖租户插件更不容易出错——鉴权链路上「租户条件被插件漏加」是不可接受的失败模式。</p>
 *
 * <p>权限码支持三种写法：{@code *}（全部）、{@code org:*}（前缀）、精确码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final JdbcTemplate jdbcTemplate;

    /** 用户的全部角色编码（多角色，可能为空）。 */
    public Set<String> rolesOf(Long userId) {
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role_code FROM sys_user_role "
                        + "WHERE tenant_id = ? AND user_id = ? AND deleted = 0",
                String.class, UserContext.tenantId(), userId);
        if (!roles.isEmpty()) {
            return new LinkedHashSet<>(roles);
        }
        // 兼容期回退：迁移只回填了「迁移时刻已存在」的用户，而演示账号与后台创建的账号
        // 是在启动之后写入的，三元组里没有记录。此时把 sys_user.role_code 视为单角色，
        // 否则这些账号会在升级后集体失去权限。
        String legacy = legacyRoleCode(userId);
        return legacy == null ? Set.of() : new LinkedHashSet<>(List.of(legacy));
    }

    private String legacyRoleCode(Long userId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT role_code FROM sys_user WHERE tenant_id = ? AND id = ? AND deleted = 0",
                String.class, UserContext.tenantId(), userId);
        return rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? null : rows.get(0);
    }

    /** 把兼容期用户的 role_code 落成一条 user_role，避免「授予新角色时把老角色弄丢」。 */
    private void seedLegacyRole(Long userId) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id = ? AND user_id = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), userId);
        if (exists != null && exists > 0) {
            return;
        }
        String legacy = legacyRoleCode(userId);
        if (legacy == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO sys_user_role (tenant_id, user_id, role_code, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                UserContext.tenantId(), userId, legacy, now, now);
    }

    /** 多角色权限取并集。 */
    public Set<String> permissionsOf(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(UserContext.tenantId());
        args.addAll(roles);
        List<String> permissions = jdbcTemplate.queryForList(
                "SELECT DISTINCT permission_code FROM sys_role_permission "
                        + "WHERE tenant_id = ? AND deleted = 0 AND role_code IN (" + placeholders + ")",
                String.class, args.toArray());
        return new LinkedHashSet<>(permissions);
    }

    public List<Map<String, Object>> listRoles() {
        return jdbcTemplate.queryForList(
                "SELECT code, name, description, built_in, status FROM sys_role "
                        + "WHERE tenant_id = ? AND deleted = 0 ORDER BY built_in DESC, code ASC",
                UserContext.tenantId());
    }

    public List<Map<String, Object>> permissionsOfRole(String roleCode) {
        return jdbcTemplate.queryForList(
                "SELECT permission_code FROM sys_role_permission "
                        + "WHERE tenant_id = ? AND role_code = ? AND deleted = 0 ORDER BY permission_code",
                UserContext.tenantId(), roleCode);
    }

    /** 给用户加一个角色（幂等）。 */
    @Transactional
    public void grant(Long userId, String roleCode) {
        assertRoleExists(roleCode);
        // 先落老角色：否则「给兼容期用户加第二个角色」会把它原来的角色挤掉
        seedLegacyRole(userId);
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role "
                        + "WHERE tenant_id = ? AND user_id = ? AND role_code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), userId, roleCode);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO sys_user_role (tenant_id, user_id, role_code, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                UserContext.tenantId(), userId, roleCode, now, now);
        log.info("授予角色 userId={} role={}", userId, roleCode);
    }

    @Transactional
    public void revoke(Long userId, String roleCode) {
        // 先落老角色，否则兼容期用户的 user_role 为空，UPDATE 影响 0 行，
        // 「撤销唯一角色」会静默返回成功，账号却在下一个请求失去全部权限
        seedLegacyRole(userId);
        int rows = jdbcTemplate.update(
                "UPDATE sys_user_role SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND role_code = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), userId, roleCode);
        // 不允许把最后一个角色撤空：撤空后该用户既不能进后台也不能做业务，属于「静默锁死账号」
        if (rows > 0) {
            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user_role WHERE tenant_id = ? AND user_id = ? AND deleted = 0",
                    Integer.class, UserContext.tenantId(), userId);
            if (remaining != null && remaining == 0) {
                throw new BizException("AUTH_LAST_ROLE", "不能撤销用户的最后一个角色");
            }
            log.info("撤销角色 userId={} role={}", userId, roleCode);
        }
    }

    /**
     * 软删角色。
     *
     * <p>不物理删除：审计日志与历史 token 里的 roleCode 仍指向它，物理删会让这些记录变成孤儿。
     * 软删后角色不再出现在列表里，也不再参与鉴权。</p>
     *
     * <p>两道拦截里第二道最容易被跳过，而它恰恰是「删完发现一批人进不去后台」的直接原因。</p>
     */
    public void deleteRole(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.badRequest("角色编码不能为空");
        }
        if (List.of("ADMIN", "STUDENT", "ORG_ADMIN").contains(code)) {
            throw BizException.badRequest("内置角色不允许删除：" + code);
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id = ? AND code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), code);
        if (exists == null || exists == 0) {
            throw BizException.notFound("角色");
        }
        Integer holders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role "
                        + "WHERE tenant_id = ? AND role_code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), code);
        if (holders != null && holders > 0) {
            throw BizException.badRequest("还有 " + holders + " 个用户持有该角色，请先解除绑定");
        }
        jdbcTemplate.update(
                "UPDATE sys_role SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND code = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), code);
    }

    public void createRole(String code, String name, String description) {
        if (code == null || code.isBlank()) {
            throw BizException.badRequest("角色编码不能为空");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id = ? AND code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), code);
        if (exists != null && exists > 0) {
            throw BizException.conflict("ROLE_EXISTS", "角色编码已存在：" + code);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, "
                        + "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, 0, 'ENABLED', ?, ?, 0)",
                UserContext.tenantId(), code, name == null || name.isBlank() ? code : name,
                description, now, now);
    }

    public void grantPermission(String roleCode, String permissionCode) {
        assertRoleExists(roleCode);
        LocalDateTime now = LocalDateTime.now();
        // 先尝试「复活」之前被撤销的行：唯一约束是 (tenant_id, role_code, permission_code)，
        // 不含 deleted 列，所以直接 INSERT 会撞约束报 500，而不是把权限授回来
        int revived = jdbcTemplate.update(
                "UPDATE sys_role_permission SET deleted = 0, updated_at = ? "
                        + "WHERE tenant_id = ? AND role_code = ? AND permission_code = ? AND deleted = 1",
                now, UserContext.tenantId(), roleCode, permissionCode);
        if (revived > 0) {
            return;
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_permission "
                        + "WHERE tenant_id = ? AND role_code = ? AND permission_code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), roleCode, permissionCode);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO sys_role_permission (tenant_id, role_code, permission_code, "
                        + "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, 0)",
                UserContext.tenantId(), roleCode, permissionCode, now, now);
    }

    /**
     * 取消角色的某个权限，与 {@link #grantPermission} 对称。
     *
     * <p>必须能撤销：授错权限后如果只能「删角色再重建」，持有该角色的用户会在重建期间
     * 失去全部权限——用一次点错换来一次全量断权，代价远大于修一个授权记录。</p>
     *
     * <p>逻辑删除而不是物理删除：`sys_role_permission` 是审计追溯「这个权限曾经属于谁」的证据。</p>
     */
    public void revokePermission(String roleCode, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            throw BizException.badRequest("权限码不能为空");
        }
        assertRoleExists(roleCode);
        int removed = jdbcTemplate.update(
                "UPDATE sys_role_permission SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND role_code = ? AND permission_code = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), roleCode, permissionCode.trim());
        if (removed == 0) {
            // 不用 notFound()：它的模板是 what + "不存在"，拼上带修饰的短语会读成
            // 「该角色未授予权限 exam:read不存在」。这里直接给完整句子
            throw new BizException("COMMON_NOT_FOUND", "该角色未授予权限 " + permissionCode);
        }
    }

    private void assertRoleExists(String roleCode) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id = ? AND code = ? AND deleted = 0",
                Integer.class, UserContext.tenantId(), roleCode);
        if (exists == null || exists == 0) {
            throw new BizException("ROLE_NOT_FOUND", "角色不存在：" + roleCode);
        }
    }
}
