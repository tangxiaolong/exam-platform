package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账用例：用 JDBC 直接造出「账实不符」的数据，再验证对账确实能检出。
 *
 * <p>为什么不用接口造数据：这几类不一致本质上是「某一步该做没做」，
 * 正常接口根本造不出来（能造出来说明业务流程本身有 bug）。
 * 直接写库是唯一能把异常态摆到对账面前的办法。</p>
 *
 * <p>断言重点不只是「有异常」，而是<b>按 biz_id 精确命中自造的那几条</b>——
 * 只断言类型会把「宽限期失效、把刚付款的订单也报了」这种误报漏掉。</p>
 */
@DisplayName("对账：订单 / 支付 / 权益三方一致性")
class ReconciliationTest extends ApiTestSupport {

    private static final long PLATFORM_TENANT = 1L;

    @Test
    @DisplayName("人工触发对账，精确检出订单/支付/权益不一致")
    void shouldDetectFabricatedAnomalies() {
        String admin = adminToken();

        long paidOrderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));
        long completedOrderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(2), "COMPLETED");
        PaidPaymentFixture mismatch = insertOrderWithPaidPayment();
        long driftedAccountId = insertDriftingAccount();

        JsonNode report = data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        assertThat(report.path("status").asText())
                .as("存在不一致时结论应为 ANOMALY，实际：%s", report)
                .isEqualTo("ANOMALY");
        assertThat(report.path("anomalyCount").asInt()).isGreaterThanOrEqualTo(4);

        assertThat(bizIds(report, "ORDER_PAID_WITHOUT_ENTITLEMENT"))
                .as("已支付超宽限期且无权益流水，必须检出")
                .contains(paidOrderId);
        assertThat(bizIds(report, "ORDER_COMPLETED_WITHOUT_ENTITLEMENT"))
                .contains(completedOrderId);
        assertThat(bizIds(report, "PAYMENT_PAID_ORDER_NOT_PAID"))
                .as("异常按订单维度上报：要修的是订单状态，支付单号放在 detail 里")
                .contains(mismatch.orderId());
        assertThat(bizIds(report, "PAYMENT_AMOUNT_MISMATCH"))
                .as("金额不一致按支付单维度上报，核对的是渠道流水")
                .contains(mismatch.paymentId());
        assertThat(bizIds(report, "ACCOUNT_LEDGER_DRIFT"))
                .contains(driftedAccountId);

        // 对账是受审计的高风险操作，人工触发必须留痕，且 target 要能定位到具体报告号
        assertThat(auditTargets("TRADE_RECON_RUN"))
                .as("每次触发都应写一条审计，target 含报告号")
                .anySatisfy(target -> assertThat(target).contains(report.path("reportNo").asText()));
    }

    @Test
    @DisplayName("宽限期内的已支付订单不误报")
    void freshPaidOrderShouldNotBeFlagged() {
        String admin = adminToken();
        long freshOrderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now());

        JsonNode report = data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        assertThat(bizIds(report, "ORDER_PAID_WITHOUT_ENTITLEMENT"))
                .as("刚付款的订单尚在发放窗口内，不应被当成异常——否则每次对账都会误报一批") 
                .doesNotContain(freshOrderId);
    }

    @Test
    @DisplayName("报告与异常明细可回查，重复对账不丢历史")
    void latestReportShouldBeQueryable() {
        String admin = adminToken();
        long firstReportSequence = reportCount();

        data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);
        data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        JsonNode latest = data("/admin/reconciliation/runs/latest", admin);
        assertThat(latest.path("reportNo").asText()).isNotBlank();
        assertThat(latest.path("scope").asText()).isEqualTo("MANUAL");
        assertThat(latest.path("startedAt").asText()).isNotBlank();

        // 每次触发都单独留一份报告：如果覆盖写，异常什么时候开始就永远查不到
        assertThat(reportCount()).isEqualTo(firstReportSequence + 2);
        assertThat(data("/admin/reconciliation/runs/recent", admin).size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("异常可处置闭环：标记后带处置人/说明，重复处置与非法状态被拒")
    void shouldCloseLoopOnAnomalyHandling() {
        String admin = adminToken();
        long paidOrderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));

        JsonNode report = data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);
        long anomalyId = anomalyId(report, "ORDER_PAID_WITHOUT_ENTITLEMENT", paidOrderId);

        String handlePath = "/admin/reconciliation/anomalies/" + anomalyId
                + "/handle?status=HANDLED&remark=已人工核对并补发权益";
        JsonNode handled = data(HttpMethod.POST, handlePath, admin, null);

        assertThat(handled.path("handleStatus").asText()).isEqualTo("HANDLED");
        assertThat(handled.path("handleRemark").asText())
                .as("处置说明要能落库：下次对账复盘时靠它判断「修了什么」")
                .isEqualTo("已人工核对并补发权益");
        assertThat(handled.path("handledBy").asLong()).isPositive();
        assertThat(handled.path("handledAt").asText()).isNotBlank();

        // 处置是受审计的高风险操作：事后要能追到「谁把这条标记为已处理」
        assertThat(auditTargets("TRADE_RECON_HANDLE"))
                .as("处置应写审计，target 含异常明细 id")
                .anySatisfy(target -> assertThat(target).contains("trade_recon_anomaly:" + anomalyId));

        // 回查最近报告时状态必须一致，否则运营刷新页面看到的还是「待处置」
        JsonNode latest = data("/admin/reconciliation/runs/latest", admin);
        JsonNode viewOfHandled = findAnomaly(latest, anomalyId);
        assertThat(viewOfHandled.path("handleStatus").asText()).isEqualTo("HANDLED");

        // 单向流转：已处置的记录不允许被第二个人覆盖，否则「谁修了什么」彻底失真
        assertThat(errorCode(HttpMethod.POST,
                "/admin/reconciliation/anomalies/" + anomalyId + "/handle?status=IGNORED", admin, null))
                .as("重复处置应报冲突而不是静默覆盖")
                .isEqualTo("TRADE_RECON_ANOMALY_ALREADY_HANDLED");

        assertThat(errorCode(HttpMethod.POST,
                "/admin/reconciliation/anomalies/" + anomalyId + "/handle?status=FOO", admin, null))
                .isEqualTo("TRADE_RECON_HANDLE_STATUS_INVALID");
    }

    @Test
    @DisplayName("处置不存在的异常返回明确错误")
    void handlingUnknownAnomalyShouldFail() {
        String admin = adminToken();
        assertThat(errorCode(HttpMethod.POST,
                "/admin/reconciliation/anomalies/99999999/handle?status=HANDLED", admin, null))
                .isEqualTo("TRADE_RECON_ANOMALY_NOT_FOUND");
    }

    @Test
    @DisplayName("检出即进指标：告警规则靠 app_recon_anomaly 主动找人")
    void detectedAnomalyShouldBeScrapable() {
        String admin = adminToken();
        insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));

        data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        String body = scrape();
        assertThat(body)
                .as("指标名与告警规则里的 app_recon_anomaly_total 必须一致，否则规则语法正确却永不触发")
                .contains("app_recon_anomaly_total");
        assertThat(body).contains("type=\"ORDER_PAID_WITHOUT_ENTITLEMENT\"");
    }

    // ---------------------------------------------------------------- 造数

    /** 一笔「支付成功但订单没推进」的造数结果，两类异常分别按订单 id / 支付单 id 上报。 */
    private record PaidPaymentFixture(long orderId, long paymentId) {
    }

    private long insertPaidOrderWithoutEntitlement(LocalDateTime paidAt) {
        return insertPaidOrderWithoutEntitlement(paidAt, "PAID");
    }

    private long insertPaidOrderWithoutEntitlement(LocalDateTime paidAt, String status) {
        long orderId = nextId("trade_order");
        jdbcTemplate.update("""
                INSERT INTO trade_order (id, tenant_id, order_no, user_id, product_id, product_name, quantity,
                                         total_amount_fen, discount_amount_fen, pay_amount_fen, status,
                                         paid_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, 1, 1, 'RECON-TEST-PRODUCT', 1, 1000, 0, 1000, ?, ?, ?, ?, 0)
                """, orderId, PLATFORM_TENANT, "RECON-" + orderId, status,
                Timestamp.valueOf(paidAt), Timestamp.valueOf(paidAt.minusMinutes(5)),
                Timestamp.valueOf(paidAt.minusMinutes(5)));
        return orderId;
    }

    /** 支付单已成功但订单仍是待支付，且金额与订单应付不一致。 */
    private PaidPaymentFixture insertOrderWithPaidPayment() {
        long orderId = nextId("trade_order");
        long paymentId = nextId("trade_payment");
        LocalDateTime now = LocalDateTime.now().minusHours(1);
        jdbcTemplate.update("""
                INSERT INTO trade_order (id, tenant_id, order_no, user_id, product_id, product_name, quantity,
                                         total_amount_fen, discount_amount_fen, pay_amount_fen, status,
                                         paid_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, 1, 1, 'RECON-TEST-PRODUCT', 1, 2000, 0, 2000, 'PENDING_PAYMENT', ?, ?, ?, 0)
                """, orderId, PLATFORM_TENANT, "RECON-" + orderId,
                Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update("""
                INSERT INTO trade_payment (id, tenant_id, order_id, order_no, pay_no, channel, amount_fen, status,
                                           transaction_id, paid_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, 'WECHAT', 1999, 'PAID', 'TX-RECON', ?, ?, ?, 0)
                """, paymentId, PLATFORM_TENANT, orderId, "RECON-" + orderId,
                "PAY-RECON-" + orderId, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return new PaidPaymentFixture(orderId, paymentId);
    }

    /** 账户总量与流水累计对不上：总量 5、流水只记了 3。 */
    private long insertDriftingAccount() {
        long accountId = nextId("entitlement_account");
        long ledgerId = nextId("entitlement_ledger");
        jdbcTemplate.update("""
                INSERT INTO entitlement_account (id, tenant_id, user_id, entitlement_type, total_amount, used_amount,
                                                 status, created_at, updated_at, deleted)
                VALUES (?, ?, 1, 'MEMBER', 5, 0, 'AVAILABLE', ?, ?, 0)
                """, accountId, PLATFORM_TENANT, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now()));
        jdbcTemplate.update("""
                INSERT INTO entitlement_ledger (id, tenant_id, user_id, account_id, entitlement_type, change_type,
                                                change_amount, balance_after, event_id, created_at, updated_at, deleted)
                VALUES (?, ?, 1, ?, 'MEMBER', 'GRANT', 3, 3, ?, ?, ?, 0)
                """, ledgerId, PLATFORM_TENANT, accountId, "RECON-EVENT-" + UUID.randomUUID(),
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
        return accountId;
    }

    /** 演示数据里已有历史主键，手工造数需要一个不会撞车的 id。 */
    private long nextId(String table) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return (max == null ? 0 : max) + 1000;
    }

    // ---------------------------------------------------------------- 断言辅助

    private List<Long> bizIds(JsonNode report, String anomalyType) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode anomaly : report.path("anomalies")) {
            if (anomalyType.equals(anomaly.path("anomalyType").asText())) {
                ids.add(anomaly.path("bizId").asLong());
            }
        }
        return ids;
    }

    /** 在报告里按「类型 + 业务对象」定位到具体的异常明细，处置接口需要的是明细 id 而不是业务 id。 */
    private long anomalyId(JsonNode report, String anomalyType, long bizId) {
        for (JsonNode anomaly : report.path("anomalies")) {
            if (anomalyType.equals(anomaly.path("anomalyType").asText())
                    && anomaly.path("bizId").asLong() == bizId) {
                return anomaly.path("id").asLong();
            }
        }
        throw new AssertionError("报告里没有 " + anomalyType + " / bizId=" + bizId + "：" + report);
    }

    private JsonNode findAnomaly(JsonNode report, long anomalyId) {
        for (JsonNode anomaly : report.path("anomalies")) {
            if (anomaly.path("id").asLong() == anomalyId) {
                return anomaly;
            }
        }
        throw new AssertionError("报告里没有异常明细 id=" + anomalyId + "：" + report);
    }

    private String scrape() {
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/actuator/prometheus",
                HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody() == null ? "" : response.getBody();
    }

    private List<String> auditTargets(String action) {
        return jdbcTemplate.queryForList(
                "SELECT target FROM sys_audit_log WHERE action = ? AND deleted = 0",
                String.class, action);
    }

    private long reportCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_recon_report WHERE deleted = 0", Long.class);
        return count == null ? 0 : count;
    }
}
