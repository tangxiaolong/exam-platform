package com.exam.platform.modules.org;

import com.exam.platform.modules.org.dto.QuotaBatchMismatch;
import com.exam.platform.modules.org.dto.QuotaMismatch;
import com.exam.platform.modules.org.dto.QuotaReconReport;
import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机构额度对账（两层）。
 *
 * <p>第一层：账户「已用」= 流水净额；第二层：流水净额 = 权益台账实际到账。
 * 第二层才是真正抓「钱扣了、权益没发出去」的那一层——此时第一层完全一致。</p>
 */
@DisplayName("机构额度对账：账户已用 / 流水净额 / 台账到账")
class OrgQuotaReconTest extends ApiTestSupport {

    @Autowired
    private OrgQuotaReconService reconService;

    @Test
    @DisplayName("发放之后账户消耗与流水净额一致，对账无差异")
    void shouldBeConsistentAfterGrant() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_A", "对账机构A");
        long orgAdminId = createUser("recon_a_admin", "对账管理员A", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("recon_a_m1", "对账成员A1", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 10));

        String orgAdmin = login("recon_a_admin");
        JsonNode grantResult = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "QUESTION_BANK", "amount", 1));
        assertThat(grantResult.path("grantedCount").asInt()).isEqualTo(2);

        assertThat(reconService.reconcileAccounts()).as("扣减与流水在同一事务里落，必须对得上").isEmpty();
        assertThat(reconService.reconcileBatches(7)).as("权益确实发到了成员手上").isEmpty();
    }

    @Test
    @DisplayName("流水净额扣掉退款：跳过成员退回的额度不算消耗")
    void shouldAccountForRefund() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_B", "对账机构B");
        JsonNode quota = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "COURSE", "amount", 10));
        long quotaId = quota.path("id").asLong();

        // 模拟一次「目标 6 人、4 人成功、2 人跳过」的发放：扣 6 退 2，账户净增 4
        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
                INSERT INTO sys_org_quota_consume (tenant_id, org_id, quota_id, batch_no, entitlement_type,
                                                  resource_id, target_count, amount_per_user, consumed_amount,
                                                  granted_count, skipped_count, refunded_amount,
                                                  created_at, updated_at, deleted)
                VALUES (?, ?, ?, 'RECON-BATCH-1', 'COURSE', 0, 6, 1, 6, 4, 2, 2, ?, ?, 0)
                """, tenantId, orgId, quotaId, now, now);
        jdbcTemplate.update("UPDATE sys_org_quota SET used_amount = 4 WHERE id = ?", quotaId);

        assertThat(reconService.reconcileAccounts()).as("净额 = 6 - 2 = 4，与账户一致").isEmpty();
    }

    @Test
    @DisplayName("账户余额被手工改动时对账能检出差异")
    void shouldDetectMismatch() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_C", "对账机构C");
        long orgAdminId = createUser("recon_c_admin", "对账管理员C", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");

        JsonNode quota = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "MEMBER", "amount", 10));
        long quotaId = quota.path("id").asLong();

        String orgAdmin = login("recon_c_admin");
        data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "MEMBER", "amount", 1));
        // 手工把已用改成 5（模拟「有扣减没落流水」或「有人直接改库」）
        jdbcTemplate.update("UPDATE sys_org_quota SET used_amount = 5 WHERE id = ?", quotaId);

        List<QuotaMismatch> mismatches = reconService.reconcileAccounts();
        assertThat(mismatches).hasSize(1);
        QuotaMismatch mismatch = mismatches.get(0);
        assertThat(mismatch.quotaId()).isEqualTo(quotaId);
        assertThat(mismatch.usedAmount()).isEqualTo(5);
        assertThat(mismatch.ledgerNet()).as("流水净额仍是真实发放的 1").isEqualTo(1);
        assertThat(mismatch.diff()).as("diff = 已用 - 流水净额").isEqualTo(4);
    }

    @Test
    @DisplayName("批次级对账抓「流水记了但权益没到账」——账户级对此完全无感")
    void shouldDetectLedgerGap() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_D", "对账机构D");
        long orgAdminId = createUser("recon_d_admin", "对账管理员D", "ORG_ADMIN");
        addMember(admin, orgId, orgAdminId, "ADMIN");
        long memberId = createUser("recon_d_m1", "对账成员D1", "STUDENT");
        addMember(admin, orgId, memberId, "MEMBER");

        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", "COURSE", "amount", 10));
        String orgAdmin = login("recon_d_admin");
        JsonNode grantResult = data(HttpMethod.POST, "/org/orgs/" + orgId + "/grants", orgAdmin,
                Map.of("entitlementType", "COURSE", "amount", 1));
        String batchNo = grantResult.path("batchNo").asText();

        // 删掉其中一条入账：模拟「权益没真正发到成员手上」
        Long ledgerId = jdbcTemplate.queryForObject(
                "SELECT id FROM entitlement_ledger WHERE event_id LIKE ? ORDER BY id DESC LIMIT 1",
                Long.class, "ORG-" + batchNo + "-%");
        jdbcTemplate.update("DELETE FROM entitlement_ledger WHERE id = ?", ledgerId);

        assertThat(reconService.reconcileAccounts())
                .as("账户与流水都还是 2，第一层看不出任何问题")
                .isEmpty();

        List<QuotaBatchMismatch> batchMismatches = reconService.reconcileBatches(7);
        assertThat(batchMismatches).hasSize(1);
        QuotaBatchMismatch mismatch = batchMismatches.get(0);
        assertThat(mismatch.batchNo()).isEqualTo(batchNo);
        assertThat(mismatch.flowNet()).isEqualTo(2);
        assertThat(mismatch.ledgerAmount()).as("台账只剩 1 份真正到账").isEqualTo(1);
        assertThat(mismatch.diff()).as("diff = 流水净额 - 台账到账").isEqualTo(1);
    }

    @Test
    @DisplayName("窗口外的流水不被选中：同一批数据，窗口不同结论不同")
    void shouldRespectLookbackWindow() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_W", "对账机构W");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();

        // 30 天前的一批：只有流水、没有台账（如果被扫到就一定报差异）
        insertConsume(tenantId, orgId, quotaId, "OLD-BATCH-1", "COURSE", 5, 0,
                LocalDateTime.now().minusDays(30));

        assertThat(reconService.reconcileBatches(7))
                .as("窗口是 7 天，30 天前的批次不该进本次核查——否则对账会随运营时间越来越慢")
                .isEmpty();
        List<QuotaBatchMismatch> wider = reconService.reconcileBatches(60);
        assertThat(wider).as("窗口放大到 60 天后，同一批数据就应该被核到").hasSize(1);
        assertThat(wider.get(0).batchNo()).isEqualTo("OLD-BATCH-1");
        assertThat(wider.get(0).flowNet()).isEqualTo(5);
        assertThat(wider.get(0).ledgerAmount()).isZero();
    }

    @Test
    @DisplayName("时间窗余量生效：台账稍早于窗口起点仍能匹配，不会误报差异")
    void shouldTolerateLedgerBeforeWindow() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_M", "对账机构M");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();
        long userId = createUser("recon_m_u1", "对账成员M1", "STUDENT");

        // 流水落在窗口内，台账却早了 30 分钟——同事务内「先逐人写台账、最后写流水」的自然结果。
        // 若没有余量补偿，这条会被当成「流水记了但权益没到账」，每天产出假告警。
        LocalDateTime windowStart = LocalDateTime.now().minusDays(7);
        insertConsume(tenantId, orgId, quotaId, "MARGIN-IN-1", "COURSE", 3, 0,
                windowStart.plusMinutes(5));
        insertLedger(tenantId, userId, "ORG-MARGIN-IN-1-" + userId, "COURSE", 3,
                windowStart.minusMinutes(30));

        assertThat(reconService.reconcileBatches(7))
                .as("余量覆盖了 30 分钟偏差，不应误报")
                .isEmpty();
    }

    @Test
    @DisplayName("余量是有界的：偏差大到超出余量时仍然报差异")
    void shouldStillDetectBeyondMargin() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_N", "对账机构N");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();
        long userId = createUser("recon_n_u1", "对账成员N1", "STUDENT");

        // 台账比窗口起点早了 90 分钟，超出 60 分钟余量：应当被当作「批次没有对应到账」
        LocalDateTime windowStart = LocalDateTime.now().minusDays(7);
        insertConsume(tenantId, orgId, quotaId, "MARGIN-OUT-1", "COURSE", 3, 0,
                windowStart.plusMinutes(5));
        insertLedger(tenantId, userId, "ORG-MARGIN-OUT-1-" + userId, "COURSE", 3,
                windowStart.minusMinutes(90));

        List<QuotaBatchMismatch> mismatches = reconService.reconcileBatches(7);
        assertThat(mismatches).as("余量只是补偿同事务写入的抖动，不是「扫全表」的借口").hasSize(1);
        assertThat(mismatches.get(0).batchNo()).isEqualTo("MARGIN-OUT-1");
        assertThat(mismatches.get(0).flowNet()).isEqualTo(3);
        assertThat(mismatches.get(0).ledgerAmount()).isZero();
    }

    @Test
    @DisplayName("账户级对账不加时间窗：used_amount 是累计值，历史消耗必须计入")
    void accountLayerShouldNotUseWindow() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_P", "对账机构P");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();
        long userId = createUser("recon_p_u1", "对账成员P1", "STUDENT");

        LocalDateTime longAgo = LocalDateTime.now().minusDays(30);
        insertConsume(tenantId, orgId, quotaId, "OLD-ACC-1", "COURSE", 4, 0, longAgo);
        insertLedger(tenantId, userId, "ORG-OLD-ACC-1-" + userId, "COURSE", 4, longAgo);
        jdbcTemplate.update("UPDATE sys_org_quota SET used_amount = 4 WHERE id = ?", quotaId);

        assertThat(reconService.reconcileAccounts())
                .as("若给账户级也加时间窗，窗口之前的消耗会被当成差异，每天产出一批假告警")
                .isEmpty();
    }

    @Test
    @DisplayName("对账报告接口：一致时 consistent=true，且回显实际扫描窗口")
    void shouldExposeReportEndpoint() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_R", "对账机构R");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();
        long userId = createUser("recon_r_u1", "对账成员R1", "STUDENT");
        insertConsume(tenantId, orgId, quotaId, "API-BATCH-1", "COURSE", 2, 0, LocalDateTime.now());
        insertLedger(tenantId, userId, "ORG-API-BATCH-1-" + userId, "COURSE", 2, LocalDateTime.now());
        jdbcTemplate.update("UPDATE sys_org_quota SET used_amount = 2 WHERE id = ?", quotaId);

        JsonNode report = data("/admin/orgs/quota-recon", admin);

        assertThat(report.path("consistent").asBoolean()).isTrue();
        assertThat(report.path("fullScan").asBoolean()).isFalse();
        assertThat(report.path("accountMismatchCount").asInt()).isZero();
        assertThat(report.path("batchMismatchCount").asInt()).isZero();
        assertThat(report.path("scannedFrom").asText())
                .as("窗口起点要回显，否则运维没法判断「核的是哪一段」")
                .isNotBlank();
        assertThat(report.path("scannedTo").asText()).isNotBlank();
    }

    @Test
    @DisplayName("全量核对能回答「是不是历史上一直有」：时间窗版本看不见历史批次")
    void fullScanShouldCoverHistory() {
        String admin = adminToken();
        long orgId = createOrg(admin, "RECON_F", "对账机构F");
        long quotaId = createQuota(admin, orgId, "COURSE", 20);
        Long tenantId = tenantId();

        // 半年前的一批只有流水、没有台账：时间窗版本看不见，全量版本必须抓到
        insertConsume(tenantId, orgId, quotaId, "LEGACY-1", "COURSE", 6, 0,
                LocalDateTime.now().minusDays(180));

        QuotaReconReport windowed = reconService.recon(7, false);
        assertThat(windowed.batchMismatches())
                .as("日常对账只看最近 7 天，历史批次不参与——这是它能长期跑下去的前提")
                .isEmpty();
        assertThat(windowed.fullScan()).isFalse();
        assertThat(windowed.scannedFrom()).isNotNull();

        QuotaReconReport full = reconService.recon(7, true);
        assertThat(full.consistent()).as("机构来投诉历史账目时，全量核对要能给出答案").isFalse();
        assertThat(full.scannedFrom()).as("全量核对没有窗口可言").isNull();
        assertThat(full.scannedTo()).isNull();
        assertThat(full.batchMismatches()).hasSize(1);
        assertThat(full.batchMismatches().get(0).batchNo()).isEqualTo("LEGACY-1");
    }

    // ------------------------------------------------------------------ helpers

    private Long tenantId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_tenant WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
    }

    private long createQuota(String admin, long orgId, String type, long amount) {
        return data(HttpMethod.POST, "/admin/orgs/" + orgId + "/quotas", admin,
                Map.of("entitlementType", type, "amount", amount)).path("id").asLong();
    }

    private void insertConsume(Long tenantId, long orgId, long quotaId, String batchNo, String type,
                               long consumed, long refunded, LocalDateTime createdAt) {
        Timestamp at = Timestamp.valueOf(createdAt);
        // 用 consumed/refunded 反推成功与跳过人数，保证「净额 = 扣减 - 退款」自洽
        int amountPerUser = 1;
        int targetCount = (int) consumed;
        int grantedCount = (int) (consumed - refunded);
        int skippedCount = (int) refunded;
        jdbcTemplate.update("""
                INSERT INTO sys_org_quota_consume (tenant_id, org_id, quota_id, batch_no, entitlement_type,
                                                  resource_id, target_count, amount_per_user, consumed_amount,
                                                  granted_count, skipped_count, refunded_amount,
                                                  created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, tenantId, orgId, quotaId, batchNo, type, targetCount, amountPerUser,
                consumed, grantedCount, skippedCount, refunded, at, at);
    }

    private void insertLedger(Long tenantId, long userId, String eventId, String type,
                              long amount, LocalDateTime createdAt) {
        Timestamp at = Timestamp.valueOf(createdAt);
        jdbcTemplate.update("""
                INSERT INTO entitlement_ledger (tenant_id, user_id, account_id, entitlement_type, resource_id,
                                                change_type, change_amount, balance_after, event_id,
                                                created_at, updated_at, deleted)
                VALUES (?, ?, 0, ?, NULL, 'GRANT', ?, ?, ?, ?, ?, 0)
                """, tenantId, userId, type, (int) amount, (int) amount, eventId, at, at);
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
