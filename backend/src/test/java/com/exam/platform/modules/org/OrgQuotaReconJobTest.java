package com.exam.platform.modules.org;

import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度对账任务的健康上报。
 *
 * <p>对账的价值全在「差异被人看见」。任务本身跑成功、差异只进日志，等于没对账——
 * 日志会滚过去，值班时既看不到「上次成功是什么时候」，也分不清「一直失败」和「调度停摆」。
 * 所以这里验的是：一致时把连续失败清零、不一致时连续失败递增（能被 stale/failure 指标抓到）。</p>
 *
 * <p>注意状态是单例、跨用例共享的（数据库每方法重建，指标不会），因此一律断言
 * 「相对变化」或「成功后归零」，不断言绝对初值。</p>
 */
@DisplayName("额度对账任务：健康上报")
class OrgQuotaReconJobTest extends ApiTestSupport {

    @Autowired
    private OrgQuotaReconJob reconJob;

    @Autowired
    private JobHealthRecorder jobHealthRecorder;

    @Test
    @DisplayName("两层都一致时上报成功：连续失败清零")
    void shouldReportSuccessWhenConsistent() {
        String admin = adminToken();
        long orgId = createOrg(admin, "JOB_A", "对账任务机构A");
        long orgAdminId = createUser("job_a_admin", "对账任务管理员A", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("job_a_m1", "对账任务成员A1", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 10));
        String orgAdmin = login("job_a_admin");
        data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1));

        reconJob.reconcile();

        assertThat(jobHealthRecorder.consecutiveFailures(OrgQuotaReconJob.JOB_NAME))
                .as("两层都对得上就不该算失败，否则真出问题时告警已经被噪声淹没")
                .isZero();
    }

    @Test
    @DisplayName("账户被改坏时上报失败：连续失败次数递增，不会只打一行日志")
    void shouldReportFailureWhenMismatched() {
        String admin = adminToken();
        long orgId = createOrg(admin, "JOB_B", "对账任务机构B");
        long orgAdminId = createUser("job_b_admin", "对账任务管理员B", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        JsonNode quota = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "MEMBER", "amount", 10));
        long quotaId = quota.path("id").asLong();
        String orgAdmin = login("job_b_admin");
        data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "MEMBER", "amount", 1));

        // 造一处「账户已用与流水净额不一致」：手工改库，正是对账要抓的场景
        jdbcTemplate.update("UPDATE sys_org_quota SET used_amount = 7 WHERE id = ?", quotaId);

        int before = jobHealthRecorder.consecutiveFailures(OrgQuotaReconJob.JOB_NAME);
        reconJob.reconcile();
        int afterFirst = jobHealthRecorder.consecutiveFailures(OrgQuotaReconJob.JOB_NAME);
        reconJob.reconcile();
        int afterSecond = jobHealthRecorder.consecutiveFailures(OrgQuotaReconJob.JOB_NAME);

        assertThat(afterFirst).as("差异必须按失败上报，否则不会被任何人看到").isEqualTo(before + 1);
        assertThat(afterSecond).as("持续失败要能累积，才能区分偶发抖动与持续故障").isEqualTo(before + 2);
    }

    // ------------------------------------------------------------------ helpers

    private long createOrg(String admin, String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", "INSTITUTION");
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
