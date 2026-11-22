package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机构门户的权限码检查。
 *
 * <p>这条用例存在的意义是：证明 {@code UserContext.requirePermission} 真能拦住人。
 * 在此之前，权限码体系只有「定义」，没有任何业务调用点，
 * 也没有任何证据说明它在 HTTP 层会产生 403。</p>
 *
 * <p>只验证拒绝路径。正常发放路径需要机构 + 成员 + 额度三层夹具，
 * 这一轮没有铺开——但拒绝检查发生在 {@code requireMember} 和业务逻辑之前，
 * 所以不需要那些夹具也能验证。</p>
 */
@DisplayName("机构门户权限码")
class OrgPortalPermissionTest extends ApiTestSupport {

    @Test
    @DisplayName("撤掉 org 域权限后，机构管理员发放权益被 403 拦住")
    void grantShouldBeForbiddenWithoutPermission() {
        // 借用管理员的密码哈希造一个机构管理员账号，避免再走一遍设密流程
        String hash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE tenant_id = 1 AND username = ?",
                String.class, ADMIN_USERNAME);

        jdbcTemplate.update(
                "INSERT INTO sys_user (tenant_id, username, password, nickname, status, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, 'org_admin_perm', ?, '权限测试机构管理员', 'ENABLED', NOW(), NOW(), 0)",
                hash);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = 'org_admin_perm'",
                Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user_role (tenant_id, user_id, role_code, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, 'ORG_ADMIN', NOW(), NOW(), 0)", userId);

        String token = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "org_admin_perm", "password", ADMIN_PASSWORD))
                .path("token").asText();

        // 把机构管理员降级成只读：org:grant 应当不再被放行
        jdbcTemplate.update(
                "UPDATE sys_role_permission SET permission_code = 'org:read' "
                        + "WHERE tenant_id = 1 AND role_code = 'ORG_ADMIN' AND permission_code = 'org:*'");
        try {
            assertThat(errorCode(HttpMethod.POST, "/org/orgs/1/grants", token,
                    Map.of("entitlementType", "MEMBER", "amount", 1)))
                    .as("缺少 org:grant 时必须在进入业务逻辑前被拦下")
                    .isEqualTo("AUTH_FORBIDDEN");
        } finally {
            // 同一上下文共享数据库：不恢复会让其它机构的用例在后续测试里莫名 403
            jdbcTemplate.update(
                    "UPDATE sys_role_permission SET permission_code = 'org:*' "
                            + "WHERE tenant_id = 1 AND role_code = 'ORG_ADMIN' AND permission_code = 'org:read'");
        }
    }
}
