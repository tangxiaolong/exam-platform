package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机构门户发放的正常路径。
 *
 * <p>和 {@link OrgPortalPermissionTest} 合起来才算完整：那条证明「没权限会被拦」，
 * 这条证明「有权限时流程仍然走得通」——只测拒绝会掩盖另一类故障：
 * 权限码写错了（比如永远是 false），所有请求都被拦，拒绝测试依然通过。</p>
 *
 * <p>不需要建额度账户：{@code OrgGrantService} 只在「账户存在」时才校验余额，
 * 账户为 null 走的是直接发放分支。</p>
 */
@DisplayName("机构门户发放")
class OrgPortalGrantTest extends ApiTestSupport {

    @Test
    @DisplayName("机构管理员给本机构发放权益成功")
    void grantShouldSucceedForOrgAdmin() {
        String hash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE tenant_id = 1 AND username = ?",
                String.class, ADMIN_USERNAME);

        jdbcTemplate.update(
                "INSERT INTO sys_user (tenant_id, username, password, nickname, status, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, 'org_admin_grant', ?, '发放测试机构管理员', 'ENABLED', NOW(), NOW(), 0)",
                hash);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = 'org_admin_grant'",
                Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user_role (tenant_id, user_id, role_code, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, 'ORG_ADMIN', NOW(), NOW(), 0)", userId);

        jdbcTemplate.update(
                "INSERT INTO sys_org (tenant_id, code, name, type, status, created_at, updated_at, deleted) "
                        + "VALUES (1, 'GRANT_TEST_ORG', '发放测试机构', 'INSTITUTION', 'ENABLED', NOW(), NOW(), 0)");
        Long orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_org WHERE tenant_id = 1 AND code = 'GRANT_TEST_ORG'", Long.class);
        // 必须是 ACTIVE 成员：requireMember 认的是成员关系，不是角色
        jdbcTemplate.update(
                "INSERT INTO sys_org_member (tenant_id, org_id, user_id, role, status, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 'ADMIN', 'ACTIVE', NOW(), NOW(), 0)", orgId, userId);

        String token = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "org_admin_grant", "password", ADMIN_PASSWORD))
                .path("token").asText();

        var result = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", token,
                Map.of("entitlementType", "MEMBER", "amount", 1));

        assertThat(result.path("batchNo").asText())
                .as("接了 org:grant 之后，正常管理员必须还能发放")
                .isNotBlank();
    }
}
