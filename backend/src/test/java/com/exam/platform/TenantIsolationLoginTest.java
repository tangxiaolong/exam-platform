package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨租户同名账号的登录隔离。
 *
 * <p>背景：唯一约束是 {@code (tenant_id, username)}，跨租户可以重名；
 * 而登录查询写的是 {@code selectOne(username = ?)}，代码里看不到租户条件。
 * 如果多租户插件没有对该表生效，这条查询会命中两行，表现为登录 500，
 * 更坏的情况是命中另一租户的同名账号。</p>
 *
 * <p>真正拦住它的是两件事同时成立：{@code applyTenant()} 在查用户之前就把租户
 * 写进了 {@code UserContext}，且插件没有把 {@code sys_user} 放进忽略名单。
 * 这两件事任何一件被改动，本用例都会红。</p>
 */
@DisplayName("登录：跨租户同名账号隔离")
class TenantIsolationLoginTest extends ApiTestSupport {

    @Test
    @DisplayName("两个租户存在同名账号时，登录不会串号也不会报错")
    void passwordLoginShouldBeIsolatedPerTenant() {
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
                Long.class, ADMIN_USERNAME);
        String hash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE id = ?", String.class, adminId);

        jdbcTemplate.update("INSERT INTO sys_tenant (code, name, status, created_at, updated_at, deleted) "
                + "VALUES ('T2', '租户二', 'ENABLED', NOW(), NOW(), 0)");
        Long t2Id = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE code = 'T2'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user (tenant_id, username, password, nickname, status, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, 'T2管理员', 'ENABLED', NOW(), NOW(), 0)",
                t2Id, ADMIN_USERNAME, hash);

        // 若隔离失效，selectOne 会命中两行 → TooManyResultsException → 这里直接失败
        JsonNode defaultLogin = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        assertThat(defaultLogin.path("token").asText())
                .as("默认租户登录不能被另一租户的同名账号干扰").isNotBlank();

        // 指定另一租户登录：同名同密码，也必须自成一路
        JsonNode otherLogin = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD, "tenantCode", "T2"));
        assertThat(otherLogin.path("token").asText()).isNotBlank();

        // 两个 token 必须属于不同用户，否则就是真的串号了
        assertThat(otherLogin.path("user").path("id").asLong())
                .as("登录到了另一个租户的同名账号").isNotEqualTo(adminId);
    }

    @Test
    @DisplayName("微信登录按 tenantCode 归属租户，不传则落平台租户")
    void wechatLoginShouldHonorTenantCode() {
        jdbcTemplate.update("INSERT INTO sys_tenant (code, name, status, created_at, updated_at, deleted) "
                + "VALUES ('T3', '租户三', 'ENABLED', NOW(), NOW(), 0)");
        Long t3Id = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE code = 'T3'", Long.class);

        // 带 tenantCode：微信用户必须建在 T3 下
        data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", "tenant3-code", "nickname", "T3考生", "tenantCode", "T3"));
        Long createdTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_user WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, t3Id);
        assertThat(createdTenant)
                .as("指定了租户却仍建在平台租户，等于机构数据被写进别人的库").isEqualTo(t3Id);

        // 不带 tenantCode：保持原行为，落平台租户
        data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", "platform-code", "nickname", "平台考生"));
        Long defaultTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM sys_user WHERE username LIKE 'wx_%' ORDER BY id DESC LIMIT 1",
                Long.class);
        assertThat(defaultTenant)
                .as("不传租户时必须保持向后兼容").isEqualTo(1L);
    }

    @Test
    @DisplayName("同一微信：同租户内复用同一账号，跨租户是两个独立账号")
    void sameWechatIsOneAccountPerTenant() {
        java.util.List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM sys_tenant WHERE code = 'T3'", Long.class);
        if (existing.isEmpty()) {
            jdbcTemplate.update("INSERT INTO sys_tenant (code, name, status, created_at, updated_at, deleted) "
                    + "VALUES ('T3', '租户三', 'ENABLED', NOW(), NOW(), 0)");
        }

        String code = "same-wechat-code";

        // 同租户内重复登录：必须复用同一个账号，不能每次都新建
        long first = data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", code, "nickname", "同微信考生"))
                .path("user").path("id").asLong();
        long second = data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", code, "nickname", "同微信考生"))
                .path("user").path("id").asLong();
        assertThat(second).as("同租户内重复登录必须是同一个账号").isEqualTo(first);

        // 换租户：是两个独立账号（租户隔离优先，账号连续性不做跨租户共享）
        long inTenant3 = data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", code, "nickname", "同微信考生", "tenantCode", "T3"))
                .path("user").path("id").asLong();
        assertThat(inTenant3).as("跨租户必须是独立账号，否则租户数据边界就破了").isNotEqualTo(first);

        // 两条身份记录各自挂在对应账号下：同一 openid 在不同租户各留一份
        Long identityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_identity "
                        + "WHERE provider = 'WECHAT' AND user_id IN (?, ?)",
                Long.class, first, inTenant3);
        assertThat(identityCount).isEqualTo(2L);
    }
}
