package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("异步报表导出：提交 → 轮询 → 下载")
class ExportFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("订单报表：提交后异步生成，状态可轮询，产物可下载")
    void orderExportShouldSucceed() throws Exception {
        String admin = adminToken();

        // 先造一笔已支付订单，保证报表非空
        String student = wechatToken("export-student", "报表考生");
        JsonNode product = data("/products?pageNo=1&pageSize=1", student).path("list").get(0);
        JsonNode order = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", product.path("id").asLong(), "quantity", 1));
        JsonNode payment = data(HttpMethod.POST, "/orders/" + order.path("id").asLong() + "/payments",
                student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        JsonNode task = data(HttpMethod.POST, "/admin/exports", admin, Map.of("type", "ORDER"));
        long taskId = task.path("id").asLong();
        assertThat(task.path("taskNo").asText()).isNotBlank();
        assertThat(task.path("type").asText()).isEqualTo("ORDER");
        assertThat(task.path("status").asText()).isIn("PENDING", "RUNNING", "SUCCESS");

        JsonNode done = waitForSuccess(taskId, admin);
        assertThat(done.path("rowCount").asInt()).isPositive();
        assertThat(done.path("fileName").asText()).endsWith(".csv");
        assertThat(done.path("downloadUrl").asText()).contains("/admin/exports/" + taskId + "/download");
        assertThat(done.path("finishedAt").asText()).isNotBlank();

        // 下载产物：CSV 含 BOM 与表头
        String csv = downloadCsv(done.path("downloadUrl").asText(), admin);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("订单号,商品名称,数量,应付金额(元),状态,下单时间,支付时间");
        assertThat(csv).contains(order.path("orderNo").asText());

        // 数据外带必须可追溯：谁在什么时候把哪一份报表拉走了
        assertThat(jdbcTemplate.queryForList(
                "SELECT target FROM sys_audit_log WHERE action = 'REPORT_EXPORT_DOWNLOAD' AND deleted = 0",
                String.class))
                .as("下载要留审计，否则报表被导出系统之后没有任何记录")
                .anySatisfy(target -> assertThat(target).contains("export_task:" + taskId));
    }

    @Test
    @DisplayName("成绩与报名报表可按场次过滤")
    void resultAndRegistrationExport() {
        String admin = adminToken();
        long sessionId = firstPublishedSessionId(admin);

        JsonNode resultTask = data(HttpMethod.POST, "/admin/exports", admin,
                Map.of("type", "RESULT", "sessionId", sessionId));
        JsonNode resultDone = waitForSuccess(resultTask.path("id").asLong(), admin);
        assertThat(resultDone.path("status").asText()).isEqualTo("SUCCESS");

        JsonNode regTask = data(HttpMethod.POST, "/admin/exports", admin,
                Map.of("type", "REGISTRATION", "sessionId", sessionId));
        JsonNode regDone = waitForSuccess(regTask.path("id").asLong(), admin);
        assertThat(regDone.path("status").asText()).isEqualTo("SUCCESS");

        String header = downloadCsv(regDone.path("downloadUrl").asText(), admin);
        assertThat(header).contains("报名ID,场次ID,项目ID,用户ID,准考证号,状态,报名时间");
    }

    @Test
    @DisplayName("非法报表类型被拒绝；未完成的任务不可下载")
    void guardRules() {
        String admin = adminToken();

        assertThat(errorCode(HttpMethod.POST, "/admin/exports", admin, Map.of("type", "UNKNOWN")))
                .isEqualTo("COMMON_BAD_REQUEST");
        assertThat(errorCode(HttpMethod.POST, "/admin/exports", admin, Map.of()))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 不存在的任务
        assertThat(errorCode(HttpMethod.GET, "/admin/exports/999999/download", admin, null))
                .isEqualTo("COMMON_NOT_FOUND");
    }

    @Test
    @DisplayName("导出任务只对提交人可见，跨租户读不到")
    void tasksShouldBeScopedToOperator() {
        String adminA = adminToken();
        String adminB = adminBToken("TENANT_EXPORT_B");

        JsonNode task = data(HttpMethod.POST, "/admin/exports", adminA, Map.of("type", "ORDER"));
        long taskId = task.path("id").asLong();

        JsonNode mine = data("/admin/exports?pageNo=1&pageSize=50", adminA);
        assertThat(mine.path("total").asLong()).isPositive();

        JsonNode others = data("/admin/exports?pageNo=1&pageSize=50", adminB);
        assertThat(others.path("total").asLong()).isZero();

        // 另一租户管理员无法下载别人的任务产物
        assertThat(errorCode(HttpMethod.GET, "/admin/exports/" + taskId + "/download", adminB, null))
                .isEqualTo("COMMON_NOT_FOUND");
    }

    // ------------------------------------------------------------------ helpers

    /** 轮询等待任务完成（异步执行，最多等约 10 秒） */
    private JsonNode waitForSuccess(long taskId, String admin) {
        JsonNode task = null;
        for (int i = 0; i < 50; i++) {
            task = data("/admin/exports/" + taskId, admin);
            String status = task.path("status").asText();
            if ("SUCCESS".equals(status)) {
                return task;
            }
            assertThat(status).as("导出任务不应失败：%s", task).isNotEqualTo("FAILED");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("导出任务超时未完成：" + task);
    }

    private String downloadCsv(String downloadUrl, String admin) {
        // downloadUrl 带 /api 前缀，去掉后用 api() 拼装
        String path = downloadUrl.replace("/api/v1", "");
        return restTemplate.exchange(api(path), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(admin)), String.class).getBody();
    }

    private org.springframework.http.HttpHeaders bearerHeaders(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private long firstPublishedSessionId(String admin) {
        JsonNode sessions = data("/admin/sessions?pageNo=1&pageSize=1", admin);
        assertThat(sessions.path("list").size()).as("演示场次应存在").isPositive();
        return sessions.path("list").get(0).path("id").asLong();
    }

    /** 新建一个独立租户的管理员，用于验证跨租户隔离 */
    private String adminBToken(String tenantCode) {
        ensureTenant(tenantCode, "报表隔离机构");
        ensureAdminUser(tenantCode, "export_admin", "123456");
        return data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "export_admin", "password", "123456", "tenantCode", tenantCode))
                .path("token").asText();
    }

    private void ensureTenant(String code, String name) {
        if (queryLong("SELECT id FROM sys_tenant WHERE code = ? AND deleted = 0", code) == null) {
            java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
            jdbcTemplate.update("INSERT INTO sys_tenant (code, name, status, created_at, updated_at, deleted) "
                    + "VALUES (?, ?, 'ENABLED', ?, ?, 0)", code, name, now, now);
        }
    }

    private void ensureAdminUser(String tenantCode, String username, String rawPassword) {
        Long tenantId = queryLong("SELECT id FROM sys_tenant WHERE code = ? AND deleted = 0", tenantCode);
        assertThat(tenantId).as("租户应已创建").isNotNull();
        if (queryLong("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ? AND deleted = 0",
                tenantId, username) == null) {
            java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
            jdbcTemplate.update("INSERT INTO sys_user (tenant_id, username, password, nickname, role_code, "
                            + "status, created_at, updated_at, deleted) "
                            + "VALUES (?, ?, ?, '报表管理员', 'ADMIN', 'ENABLED', ?, ?, 0)",
                    tenantId, username, passwordEncoder.encode(rawPassword), now, now);
        }
    }

    private Long queryLong(String sql, Object... args) {
        var results = jdbcTemplate.queryForList(sql, Long.class, args);
        return results.isEmpty() ? null : results.get(0);
    }
}
