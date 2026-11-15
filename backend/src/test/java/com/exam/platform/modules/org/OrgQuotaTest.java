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
 * 机构额度池。
 *
 * <p>验三件事：充值按份数扣减、余额不足时整批拒绝且一分不扣、
 * 以及没有额度账户的机构仍按旧逻辑发放（额度池是可选能力，不能变成所有机构的新门槛）。</p>
 */
@DisplayName("机构额度池：充值、扣减、余额不足拒绝")
class OrgQuotaTest extends ApiTestSupport {

    @Test
    @DisplayName("充值 100、发 2 人后余额 98，机构侧能看到账单")
    void shouldConsumeOnGrant() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_A", "额度机构A");
        long orgAdminId = createUser("quota_a_admin", "额度管理员A", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberA = createUser("quota_a_m1", "额度成员A1", "STUDENT");
        long memberB = createUser("quota_a_m2", "额度成员A2", "STUDENT");
        addMember(admin, orgId, memberA, "MEMBER");
        addMember(admin, orgId, memberB, "MEMBER");

        JsonNode recharged = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 100, "remark", "采购单 PO-001"));
        assertThat(recharged.path("totalAmount").asLong()).isEqualTo(100L);
        assertThat(recharged.path("usedAmount").asLong()).isZero();

        String orgAdmin = login("quota_a_admin");
        JsonNode result = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1));
        assertThat(result.path("grantedCount").asInt()).isEqualTo(3);

        // 3 人 × 1 份 = 扣 3
        assertThat(quotaField(admin, orgId, "usedAmount")).isEqualTo(3L);
        assertThat(quotaField(admin, orgId, "totalAmount")).isEqualTo(100L);

        // 机构侧也能查到同一份账单
        JsonNode portalQuotas = data("/org/orgs/" + orgId + "/quotas", orgAdmin);
        assertThat(portalQuotas.get(0).path("usedAmount").asLong()).isEqualTo(3L);

        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM sys_audit_log WHERE action = 'ORG_QUOTA_RECHARGE'", String.class))
                .as("充值要留审计：钱和额度都不能只靠一个人记")
                .isNotEmpty();
    }

    @Test
    @DisplayName("余额不足：整批拒绝，额度一分不扣、权益一份不发")
    void shouldRejectWhenInsufficient() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_B", "额度机构B");
        long orgAdminId = createUser("quota_b_admin", "额度管理员B", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("quota_b_m1", "额度成员B1", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1));

        String orgAdmin = login("quota_b_admin");
        // 2 名有效成员 × 1 份 = 2 > 余额 1
        assertThat(errorCode(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1)))
                .isEqualTo("ORG_QUOTA_INSUFFICIENT");

        assertThat(quotaField(admin, orgId, "usedAmount"))
                .as("被拒绝的批次不能扣额度，否则机构会凭空少一份")
                .isZero();
    }

    @Test
    @DisplayName("没有额度账户的机构不受影响：仍按旧逻辑发放")
    void shouldGrantWithoutQuota() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_C", "额度机构C");
        long orgAdminId = createUser("quota_c_admin", "额度管理员C", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        String orgAdmin = login("quota_c_admin");
        JsonNode result = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "COURSE", "amount", 1));
        assertThat(result.path("grantedCount").asInt()).isPositive();
        assertThat(data("/org/orgs/" + orgId + "/quotas", orgAdmin).size()).isZero();
    }

    @Test
    @DisplayName("额度过期后不可消耗，也不会退化成「不限额」")
    void shouldRejectExpiredQuota() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_E", "额度机构E");
        long orgAdminId = createUser("quota_e_admin", "额度管理员E", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("quota_e_m1", "额度成员E1", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        // 上一期采购的额度，到期日已过。接口不允许把期限改到过去，所以这里直接造库中状态
        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
                INSERT INTO sys_org_quota (tenant_id, org_id, entitlement_type, resource_id, total_amount,
                                           used_amount, remark, expire_at, created_at, updated_at, deleted)
                VALUES (?, ?, 'QUESTION_BANK', 0, 10, 0, '上期采购', ?, ?, ?, 0)
                """, tenantId, orgId, Timestamp.valueOf(LocalDateTime.now().minusDays(1)), now, now);

        String orgAdmin = login("quota_e_admin");
        assertThat(errorCode(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1)))
                .as("过期账户必须拒绝：否则额度一到期就等于绕过了限额")
                .isEqualTo("ORG_QUOTA_EXPIRED");

        JsonNode quotas = data("/org/orgs/" + orgId + "/quotas", orgAdmin);
        assertThat(quotas.get(0).path("usedAmount").asLong()).as("被拒的批次不扣额度").isZero();
    }

    @Test
    @DisplayName("续充只能把有效期往后推：更早的日期被拒，更晚的日期生效并累加")
    void shouldExtendExpiryOnlyForwards() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_F", "额度机构F");
        long orgAdminId = createUser("quota_f_admin", "额度管理员F", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        String tomorrow = LocalDateTime.now().plusDays(1).withNano(0).toString();
        JsonNode first = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "COURSE", "amount", 10, "expireAt", tomorrow));
        assertThat(first.path("totalAmount").asLong()).isEqualTo(10L);
        assertThat(first.path("expireAt").asText()).startsWith(tomorrow.substring(0, 10));

        // 想把期限改到过去：接口直接拒绝，而不是静默截断成「已过期」
        assertThat(errorCode(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "COURSE", "amount", 5,
                        "expireAt", LocalDateTime.now().minusDays(1).withNano(0).toString())))
                .isEqualTo("COMMON_BAD_REQUEST");

        String nextMonth = LocalDateTime.now().plusDays(30).withNano(0).toString();
        JsonNode second = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "COURSE", "amount", 5, "expireAt", nextMonth));
        assertThat(second.path("totalAmount").asLong()).as("同一账户累加").isEqualTo(15L);
        assertThat(second.path("expireAt").asText()).as("期限只往后推").startsWith(nextMonth.substring(0, 10));
    }

    @Test
    @DisplayName("充值落流水：每笔采购一行，流水合计必须等于账户累计")
    void shouldWriteRechargeLedger() {
        String admin = adminToken();
        long orgId = createOrg(admin, "QUOTA_R", "额度机构R");
        long orgAdminId = createUser("quota_r_admin", "额度管理员R", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 30, "remark", "PO-001"));
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 20, "remark", "PO-002",
                        "expireAt", LocalDateTime.now().plusDays(10).withNano(0).toString()));

        JsonNode recharges = data("/admin/orgs/" + orgId + "/quota-recharges", admin);
        assertThat(recharges.size()).as("两笔采购就是两行流水").isEqualTo(2);
        long sum = 0;
        for (JsonNode node : recharges) {
            sum += node.path("amount").asLong();
            assertThat(node.path("operatorId").asLong()).as("后台充值必须记下操作人").isPositive();
        }
        assertThat(sum)
                .as("流水合计要等于账户累计：明细与汇总对不上就说明有充值没落流水")
                .isEqualTo(quotaField(admin, orgId, "totalAmount"));

        // 机构侧也能看到自己的采购记录
        String orgAdmin = login("quota_r_admin");
        assertThat(data("/org/orgs/" + orgId + "/quota-recharges", orgAdmin).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("过期报表列出过期账户与未用余额，长期有效的账户不进报表")
    void shouldListExpiredQuotas() {
        String admin = adminToken();
        long expiredOrgId = createOrg(admin, "QUOTA_X", "过期额度机构");
        long normalOrgId = createOrg(admin, "QUOTA_Y", "正常额度机构");

        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
                INSERT INTO sys_org_quota (tenant_id, org_id, entitlement_type, resource_id, total_amount,
                                           used_amount, remark, expire_at, created_at, updated_at, deleted)
                VALUES (?, ?, 'QUESTION_BANK', 0, 10, 3, '上期采购', ?, ?, ?, 0)
                """, tenantId, expiredOrgId, Timestamp.valueOf(LocalDateTime.now().minusDays(5)), now, now);

        data(HttpMethod.POST, "/admin/orgs/" + normalOrgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 10));

        JsonNode expired = data("/admin/orgs/expired-quotas", admin);
        assertThat(expired.size()).as("只有过期账户进报表").isEqualTo(1);
        assertThat(expired.get(0).path("orgId").asLong()).isEqualTo(expiredOrgId);
        assertThat(expired.get(0).path("orgName").asText()).isEqualTo("过期额度机构");
        assertThat(expired.get(0).path("balance").asLong()).as("作废了多少要看得见").isEqualTo(7);
    }

    // ------------------------------------------------------------------ helpers

    private long quotaField(String admin, long orgId, String field) {
        JsonNode quotas = data("/admin/orgs/" + orgId + "/quotas", admin);
        assertThat(quotas.size()).as("应存在一条额度账户").isEqualTo(1);
        return quotas.get(0).path(field).asLong();
    }

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
