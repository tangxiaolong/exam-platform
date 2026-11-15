package com.exam.platform.modules.trade;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账异常「跨轮次待处置池」与导出。
 *
 * <p>和 {@code /runs/latest} 的分工是这两条用例的核心：最新报告回答「这一轮查出什么」，
 * 待处置池回答「平台上还有多少笔没对上」。后者跨轮次，所以处置完一轮不会让数字掉到 0，
 * 除非真的都处理完了。</p>
 */
@DisplayName("对账异常：跨轮次待处置池与导出")
class ReconAnomalyPoolTest extends ApiTestSupport {

    private static final long PLATFORM_TENANT = 1L;

    @Test
    @DisplayName("待处置池跨轮次累积，按处置状态过滤能区分「还没人管」与「已处理」")
    void poolShouldSpanReportsAndFilterByStatus() {
        String admin = adminToken();
        long orderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));

        JsonNode report = data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);
        long anomalyId = anomalyIdOf(report, orderId);
        assertThat(anomalyId).as("前置条件：对账应检出这条异常").isPositive();

        JsonNode pending = data("/admin/reconciliation/anomalies?handleStatus=PENDING", admin);
        assertThat(pending.path("total").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(bizIds(pending)).contains(orderId);

        // 处置后应从「待处置」里消失，落到「已处理」——这正是运营看板上两个数字的分界
        data(HttpMethod.POST,
                "/admin/reconciliation/anomalies/" + anomalyId + "/handle?status=HANDLED&remark=已补发权益",
                admin, null);

        JsonNode pendingAfter = data("/admin/reconciliation/anomalies?handleStatus=PENDING", admin);
        assertThat(bizIds(pendingAfter)).doesNotContain(orderId);
        JsonNode handled = data("/admin/reconciliation/anomalies?handleStatus=HANDLED", admin);
        assertThat(bizIds(handled)).contains(orderId);
    }

    @Test
    @DisplayName("按业务类型过滤：订单类异常不会被支付类筛选带出来")
    void poolShouldFilterByBizType() {
        String admin = adminToken();
        long orderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));
        data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        JsonNode orders = data("/admin/reconciliation/anomalies?bizType=ORDER", admin);
        assertThat(bizIds(orders)).contains(orderId);

        JsonNode payments = data("/admin/reconciliation/anomalies?bizType=PAYMENT", admin);
        assertThat(bizIds(payments)).doesNotContain(orderId);
    }

    @Test
    @DisplayName("导出 CSV：带 BOM 与表头，只含筛选范围的行")
    void exportShouldProduceCsvWithHeaderAndBom() {
        String admin = adminToken();
        long orderId = insertPaidOrderWithoutEntitlement(LocalDateTime.now().minusHours(1));
        data(HttpMethod.POST, "/admin/reconciliation/runs", admin, null);

        String csv = downloadCsv(admin, "/admin/reconciliation/anomalies/export?handleStatus=PENDING");
        assertThat(csv)
                .as("BOM + 表头：Excel 直接打开中文不乱码，财务不用先另存为")
                .startsWith("\uFEFF异常ID");
        assertThat(csv).contains("RECON-" + orderId);
        assertThat(csv).contains("PENDING");

        // 筛选条件必须真的生效：给财务的文件不能混进已处理的记录
        String handledOnly = downloadCsv(admin, "/admin/reconciliation/anomalies/export?handleStatus=HANDLED");
        assertThat(handledOnly).doesNotContain("RECON-" + orderId);

        // 这份文件会离开系统交给财务，导出本身必须留痕
        Long audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE action = 'TRADE_RECON_EXPORT' AND deleted = 0",
                Long.class);
        assertThat(audits).as("导出异常清单应写审计").isPositive();
    }

    @Test
    @DisplayName("没有异常时池子为空、导出只有表头")
    void emptyPoolShouldStillExportHeader() {
        String admin = adminToken();
        JsonNode pool = data("/admin/reconciliation/anomalies", admin);
        assertThat(pool.path("total").asLong()).isZero();

        String csv = downloadCsv(admin, "/admin/reconciliation/anomalies/export");
        assertThat(csv.strip()).isEqualTo("\uFEFF异常ID,报告ID,检出类型,业务类型,业务编号,期望值,实际值,详情,处置状态,处置说明,处置人,检出时间,处置时间");
    }

    // ---------------------------------------------------------------- 造数与断言辅助

    private long insertPaidOrderWithoutEntitlement(LocalDateTime paidAt) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM trade_order", Long.class);
        long orderId = (max == null ? 0 : max) + 1000;
        jdbcTemplate.update("""
                INSERT INTO trade_order (id, tenant_id, order_no, user_id, product_id, product_name, quantity,
                                         total_amount_fen, discount_amount_fen, pay_amount_fen, status,
                                         paid_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, 1, 1, 'POOL-TEST-PRODUCT', 1, 1000, 0, 1000, 'PAID', ?, ?, ?, 0)
                """, orderId, PLATFORM_TENANT, "RECON-" + orderId,
                Timestamp.valueOf(paidAt), Timestamp.valueOf(paidAt.minusMinutes(5)),
                Timestamp.valueOf(paidAt.minusMinutes(5)));
        return orderId;
    }

    private long anomalyIdOf(JsonNode report, long bizId) {
        for (JsonNode anomaly : report.path("anomalies")) {
            if (anomaly.path("bizId").asLong() == bizId) {
                return anomaly.path("id").asLong();
            }
        }
        throw new AssertionError("报告里没有 bizId=" + bizId + "：" + report);
    }

    private java.util.List<Long> bizIds(JsonNode page) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode row : page.path("list")) {
            ids.add(row.path("bizId").asLong());
        }
        return ids;
    }

    /** 导出返回 text/csv 而不是 JSON，所以绕开 {@code call()} 的 JSON 断言。 */
    private String downloadCsv(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<byte[]> response = restTemplate.exchange(api(path), HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .as("导出必须声明 csv 类型，浏览器才会当文件下载")
                .isNotNull();
        byte[] body = response.getBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
