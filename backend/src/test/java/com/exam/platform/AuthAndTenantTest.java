package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("身份、权限与租户隔离")
class AuthAndTenantTest extends ApiTestSupport {

    @Test
    @DisplayName("管理员账号密码登录后可获取本人资料")
    void adminLoginShouldReturnTokenAndProfile() {
        String token = adminToken();

        JsonNode me = data("/users/me", token);

        assertThat(token).isNotBlank();
        assertThat(me.path("username").asText()).isEqualTo(ADMIN_USERNAME);
        assertThat(me.path("roleCode").asText()).isEqualTo("ADMIN");
        assertThat(me.path("status").asText()).isEqualTo("ENABLED");
    }

    @Test
    @DisplayName("未携带 token 访问受保护接口返回 AUTH_UNAUTHORIZED")
    void anonymousRequestShouldBeRejected() {
        String code = errorCode(HttpMethod.GET, "/attempts", null, null);

        assertThat(code).isEqualTo("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("密码错误返回 AUTH_BAD_CREDENTIALS，且不泄漏账号是否存在")
    void wrongPasswordShouldBeRejected() {
        JsonNode existUser = call(HttpMethod.POST, "/auth/login", null,
                Map.of("username", ADMIN_USERNAME, "password", "wrong-password"));
        JsonNode noSuchUser = call(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "ghost-user", "password", "wrong-password"));

        assertThat(existUser.path("code").asText()).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(noSuchUser.path("code").asText()).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(existUser.path("message").asText()).isEqualTo(noSuchUser.path("message").asText());
    }

    @Test
    @DisplayName("考生 token 访问运营后台接口返回 AUTH_FORBIDDEN")
    void studentShouldNotAccessAdminApi() {
        String studentToken = wechatToken("auth-test-student", "越权测试考生");

        String code = errorCode(HttpMethod.GET, "/admin/sessions", studentToken, null);

        assertThat(code).isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("不同租户的数据互不可见，且账号名按租户隔离")
    void shouldIsolateTenantData() {
        long tenantId = ensureTenant("TENANT_T2", "隔离测试机构");
        ensureUser(tenantId, "t2admin", "123456", "ADMIN");

        String t2Token = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "t2admin", "password", "123456", "tenantCode", "TENANT_T2"))
                .path("token").asText();

        JsonNode me = data("/users/me", t2Token);
        JsonNode projects = data("/projects?pageNo=1&pageSize=10", t2Token);

        assertThat(me.path("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(projects.path("total").asLong()).as("租户 2 不应看到平台租户的项目").isZero();

        // 同名账号在平台租户下不存在 → 用户名按租户隔离
        String platformLogin = errorCode(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "t2admin", "password", "123456"));
        assertThat(platformLogin).isEqualTo("AUTH_BAD_CREDENTIALS");

        // 平台租户本身有演示项目，作为对照
        JsonNode platformProjects = data("/projects?pageNo=1&pageSize=10", adminToken());
        assertThat(platformProjects.path("total").asLong()).isPositive();
    }

    @Test
    @DisplayName("指定不存在的租户登录返回 TENANT_NOT_FOUND")
    void unknownTenantShouldBeRejected() {
        String code = errorCode(HttpMethod.POST, "/auth/login", null,
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD, "tenantCode", "NOT_EXIST_TENANT"));

        assertThat(code).isEqualTo("TENANT_NOT_FOUND");
    }

    private long ensureTenant(String code, String name) {
        Long id = queryLong("SELECT id FROM sys_tenant WHERE code = ? AND deleted = 0", code);
        if (id != null) {
            return id;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("INSERT INTO sys_tenant (code, name, status, created_at, updated_at, deleted) "
                + "VALUES (?, ?, 'ENABLED', ?, ?, 0)", code, name, now, now);
        return queryLong("SELECT id FROM sys_tenant WHERE code = ? AND deleted = 0", code);
    }

    private void ensureUser(long tenantId, String username, String rawPassword, String roleCode) {
        Long id = queryLong("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ? AND deleted = 0",
                tenantId, username);
        if (id != null) {
            return;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id, username, password, nickname, role_code, status, "
                        + "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, 'ENABLED', ?, ?, 0)",
                tenantId, username, passwordEncoder.encode(rawPassword), "隔离测试管理员", roleCode, now, now);
    }

    private Long queryLong(String sql, Object... args) {
        var results = jdbcTemplate.queryForList(sql, Long.class, args);
        return results.isEmpty() ? null : results.get(0);
    }
}
