package com.exam.platform.modules.org;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机构侧门户（B 端自助）。
 *
 * <p>验的是三条边界，任何一条破了这套自助就变成越权工具：
 * 角色是门票但不是边界（成员关系要每次重查）、机构 ID 只认路径（body 里的机构必须被丢弃）、
 * 停用机构不接受自助发放。</p>
 */
@DisplayName("机构侧门户：机构管理员自助查看与发放")
class OrgPortalTest extends ApiTestSupport {

    @Test
    @DisplayName("能看到自己所属的机构与成员；别人家机构与考生身份都被拒")
    void shouldListOwnOrgsAndMembers() {
        String admin = adminToken();
        long orgId = createOrg(admin, "PORTAL_A", "机构门户A", null);
        long orgAdminId = createUser("portal_admin", "门户管理员", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("portal_member", "门户成员", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        String orgAdmin = login("portal_admin");

        JsonNode myOrgs = data("/org/my-orgs", orgAdmin);
        assertThat(myOrgs.size()).isEqualTo(1);
        assertThat(myOrgs.get(0).path("orgId").asLong()).isEqualTo(orgId);
        assertThat(myOrgs.get(0).path("orgName").asText()).isEqualTo("机构门户A");
        assertThat(myOrgs.get(0).path("myRole").asText()).as("要能看出自己在机构里的角色").isEqualTo("ADMIN");

        JsonNode members = data("/org/orgs/" + orgId + "/members", orgAdmin);
        assertThat(members.size()).isEqualTo(2);

        // 另一家机构：他是平台上的 ORG_ADMIN，但不是这家机构的成员
        long otherOrg = createOrg(admin, "PORTAL_B", "机构门户B", null);
        assertThat(errorCode(HttpMethod.GET, "/org/orgs/" + otherOrg + "/profile", orgAdmin, null))
                .as("角色不能替代成员关系")
                .isEqualTo("ORG_NOT_MEMBER");

        // 普通考生连门票都没有
        String student = wechatToken("portal-student", "普通考生");
        assertThat(errorCode(HttpMethod.GET, "/org/my-orgs", student, null)).isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("自助发放：发给本机构成员、动作单独留痕，请求体里的机构被忽略")
    void shouldGrantToOwnMembers() {
        String admin = adminToken();
        long orgId = createOrg(admin, "PORTAL_G", "机构门户G", null);
        long orgAdminId = createUser("portal_g_admin", "门户管理员G", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("portal_g_member", "门户成员G", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        String orgAdmin = login("portal_g_admin");

        Map<String, Object> body = new HashMap<>();
        body.put("entitlementType", "QUESTION_BANK");
        body.put("amount", 1);
        // 刻意在 body 里指向另一个机构：服务端必须以路径为准，丢弃这个值
        body.put("orgId", 999999L);

        JsonNode result = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin, body);
        assertThat(result.path("orgId").asLong()).as("发放目标只能是路径上的机构").isEqualTo(orgId);
        assertThat(result.path("grantedCount").asInt()).as("两名有效成员都应拿到").isEqualTo(2);
        assertThat(result.path("batchNo").asText()).isNotBlank();

        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM sys_audit_log WHERE action = 'ORG_PORTAL_GRANT'", String.class))
                .as("机构自助发放要留审计，且动作码与平台代发区分开")
                .isNotEmpty();
    }

    @Test
    @DisplayName("停用机构不接受自助发放与查看")
    void shouldRejectDisabledOrg() {
        String admin = adminToken();
        long orgId = createOrg(admin, "PORTAL_D", "机构门户D", "DISABLED");
        long orgAdminId = createUser("portal_d_admin", "门户管理员D", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        String orgAdmin = login("portal_d_admin");

        assertThat(errorCode(HttpMethod.GET, "/org/orgs/" + orgId + "/profile", orgAdmin, null))
                .isEqualTo("ORG_DISABLED");
        assertThat(errorCode(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK")))
                .isEqualTo("ORG_DISABLED");
    }

    // ------------------------------------------------------------------ helpers

    private long createOrg(String admin, String code, String name, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", "INSTITUTION");
        if (status != null) {
            body.put("status", status);
        }
        return data(HttpMethod.POST, "/admin/orgs", admin, body).path("id").asLong();
    }

    private long createUser(String username, String nickname, String roleCode) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO sys_user (tenant_id, username, password, nickname, role_code, status,
                                      created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', ?, ?, 0)
                """, tenantId, username, passwordEncoder.encode("123456"), nickname, roleCode, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?",
                Long.class, username);
    }

    private void addMember(String admin, long orgId, long userId, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        if (role != null) {
            body.put("role", role);
        }
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, body);
    }

    private String login(String username) {
        return data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", username, "password", "123456")).path("token").asText();
    }
}
