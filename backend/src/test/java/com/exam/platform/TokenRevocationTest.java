package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色权限变更后，已签发的 token 是否立即失效。
 *
 * <p>结论：**立即生效**。{@code AuthInterceptor} 每次请求都用
 * {@code roleService.rolesOf(userId)} 实时查库，token 里只带身份不带权限。
 * 代价是每请求多一次角色查询，换来的是不需要黑名单、不必等 token 过期。</p>
 *
 * <p>这条测试的价值是**锁住这个设计**：若将来有人为省一次查询把权限塞进 JWT，
 * 撤销就会退化成「等过期才生效」，而这种退化很难在评审里被发现。</p>
 */
@DisplayName("权限撤销：旧 token 立即失效")
class TokenRevocationTest extends ApiTestSupport {

    /** 用于手工签发「没有 iatMs」的老 token，验证回退分支。 */
    @org.springframework.beans.factory.annotation.Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("撤掉角色后，同一个 token 立刻失去后台访问权")
    void revokeShouldTakeEffectOnExistingToken() {
        String admin = adminToken();
        String student = wechatToken("revoke-student", "撤权考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);

        // 未授权前进不去后台
        assertThat(errorCode(HttpMethod.GET, "/admin/roles", student, null))
                .isEqualTo("AUTH_FORBIDDEN");

        // 授予管理员角色后，同一个 token 立即可用
        data(HttpMethod.POST, "/admin/users/" + userId + "/roles?roleCode=ADMIN", admin, null);
        assertThat(data("/admin/roles", student).size()).isPositive();

        // 撤销后，还是同一个 token，必须立刻被拒
        data(HttpMethod.DELETE, "/admin/users/" + userId + "/roles/ADMIN", admin, null);
        assertThat(errorCode(HttpMethod.GET, "/admin/roles", student, null))
                .as("权限撤销必须对已签发的 token 立即生效")
                .isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("停用账号后，已签发的 token 立刻失效")
    void disablingUserShouldInvalidateExistingToken() {
        String student = wechatToken("disabled-student", "停用考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);

        // 停用前：正常访问
        assertThat(data("/wrong-questions", student).size()).isZero();

        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = ?", userId);
        assertThat(errorCode(HttpMethod.GET, "/wrong-questions", student, null))
                .as("停用是应急止血手段，要等 token 过期就失去意义")
                .isEqualTo("AUTH_UNAUTHORIZED");

        // 恢复正常后，同一个 token 立刻又能用
        jdbcTemplate.update("UPDATE sys_user SET status = 'ENABLED' WHERE id = ?", userId);
        assertThat(data("/wrong-questions", student).size()).isZero();
    }

    @Test
    @DisplayName("改密码后旧 token 立刻失效，重新登录拿到的新 token 不受影响")
    void passwordChangeShouldInvalidateOldToken() throws InterruptedException {
        String student = wechatToken("pwd-student", "改密考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);
        assertThat(data("/wrong-questions", student).size()).isZero();

        // 模拟改密码：失效截止时间推到「现在之后」，旧 token 的 iat 必然早于它。
        // 间隔取 1 秒而非更小：JWT 的 iat 只精确到秒，间隔小于 1 秒时新旧 token 会落在同一秒，
        // 判断结果取决于毫秒抖动，测试会时绿时红。
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusSeconds(1)), userId);
        assertThat(errorCode(HttpMethod.GET, "/wrong-questions", student, null))
                .as("改密码的意义就是让别处的登录态作废").isEqualTo("AUTH_UNAUTHORIZED");

        // 等过截止时间后重新登录：新 token 的签发时间更新，必须立刻可用
        Thread.sleep(2600);
        String fresh = wechatToken("pwd-student", "改密考生");
        assertThat(data("/wrong-questions", fresh).size())
                .as("新 token 不能也被误伤").isZero();
    }

    @Test
    @DisplayName("改密码接口：校验原密码、旧 token 立刻失效、新密码可登录")
    void changePasswordEndpointShouldWork() {
        String admin = adminToken();
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 AND username = ?",
                Long.class, ADMIN_USERNAME);
        String originalHash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE id = ?", String.class, userId);

        try {
            // 原密码错误：拒绝，且不能改成功
            assertThat(errorCode(HttpMethod.PUT, "/users/me/password", admin,
                    java.util.Map.of("oldPassword", "definitely-wrong", "newPassword", "NewPass1234")))
                    .isEqualTo("COMMON_BAD_REQUEST");

            // 正确原密码：改成功
            data(HttpMethod.PUT, "/users/me/password", admin,
                    java.util.Map.of("oldPassword", ADMIN_PASSWORD, "newPassword", "NewPass1234"));

            // 旧 token 立刻失效——这才是改密码的安全意义
            assertThat(errorCode(HttpMethod.GET, "/admin/roles", admin, null))
                    .isEqualTo("AUTH_UNAUTHORIZED");

            // 新密码能登录，且拿到的 token 可用
            String fresh = data(HttpMethod.POST, "/auth/login", null, java.util.Map.of(
                    "username", ADMIN_USERNAME, "password", "NewPass1234")).path("token").asText();
            assertThat(data("/admin/roles", fresh).size()).isPositive();
        } finally {
            // 直接还原哈希：不能靠再调一次接口，否则受新密码长度规则约束
            jdbcTemplate.update(
                    "UPDATE sys_user SET password = ?, token_invalid_before = NULL WHERE id = ?",
                    originalHash, userId);
        }
    }

    @Test
    @DisplayName("没有 iatMs 的老 token：回退秒级签发时间，仍能正确判定失效")
    void legacyTokenWithoutIatMsShouldStillBeJudged() {
        String student = wechatToken("legacy-iat", "老token考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);

        // 10 秒前签发，且不带 iatMs——模拟本次改动之前发出的 token。
        // 取 10 秒是为了避开秒级截断：iat 在 JWT 里只存整秒，
        // 距离太近时「早/晚于」会落在同一秒上，测试就变成掷骰子。
        long issuedAtMs = System.currentTimeMillis() - 10_000L;
        String legacy = legacyToken(userId, issuedAtMs);

        // ① 失效时间早于签发时间 → 不该被判定失效（回退取到了正确的 iat）
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = ? WHERE id = ?",
                new java.sql.Timestamp(issuedAtMs - 5_000L), userId);
        assertThat(data("/wrong-questions", legacy).size())
                .as("失效时间早于签发时间时不能误杀").isZero();

        // ② 失效时间晚于签发时间 → 必须失效。
        // 这一条是回退分支的真正证据：若 iat 回退没生效（tokenIssuedAt 为 null），这里会放行
        jdbcTemplate.update("UPDATE sys_user SET token_invalid_before = ? WHERE id = ?",
                new java.sql.Timestamp(issuedAtMs + 5_000L), userId);
        assertThat(errorCode(HttpMethod.GET, "/wrong-questions", legacy, null))
                .as("老 token 必须能被改密码作废，否则改密对存量会话无效")
                .isEqualTo("AUTH_UNAUTHORIZED");
    }

    /** 按 {@code TokenService.issue} 的 claims 结构手工签发，但故意不带 iatMs。 */
    private String legacyToken(Long userId, long issuedAtMs) {
        java.util.Date issued = new java.util.Date(issuedAtMs);
        return io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", 1L)
                .claim("role", "STUDENT")
                .issuedAt(issued)
                .expiration(new java.util.Date(issuedAtMs + 3_600_000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }
}
