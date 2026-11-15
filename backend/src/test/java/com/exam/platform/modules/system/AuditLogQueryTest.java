package com.exam.platform.modules.system;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志查询：写入 → 可查。
 *
 * <p>此前审计只有写没有读，记录落库后除了直连数据库没有任何出口。这里验的是
 * 「运营能不能按动作/人/时间查到那条记录」，以及考生拿不到审计数据。</p>
 *
 * <p>每个用例独立重建库（见 {@code AbstractApiTestSupport}），所以审计表在用例开始是空的，
 * 断言总数不需要减去历史数据。</p>
 */
@DisplayName("审计日志查询")
class AuditLogQueryTest extends ApiTestSupport {

    @Test
    @DisplayName("管理端高危操作会落审计，且能按动作查询到")
    void shouldRecordAndQueryAuditLog() {
        String admin = adminToken();
        JsonNode project = data(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", "AUDIT-CASE-1", "name", "Audit Case Project"));

        JsonNode page = data("/admin/audit-logs?action=PROJECT_CREATE&pageNo=1&pageSize=10", admin);
        assertThat(page.path("total").asLong()).isEqualTo(1L);
        JsonNode first = page.path("list").get(0);
        assertThat(first.path("action").asText()).isEqualTo("PROJECT_CREATE");
        assertThat(first.path("username").asText()).as("审计要能回答「谁做的」").isEqualTo(ADMIN_USERNAME);
        assertThat(first.path("target").asText())
                .as("审计要能回答「对哪个对象做的」")
                .isEqualTo("exam_project:" + project.path("id").asLong());
        assertThat(first.path("detail").asText()).isEqualTo("Audit Case Project");
        assertThat(first.path("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("按操作人过滤：模糊匹配能查到，查不到的人返回空")
    void shouldFilterByUsername() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", "AUDIT-CASE-2", "name", "Audit Filter Project"));

        JsonNode hit = data("/admin/audit-logs?username=adm", admin);
        assertThat(hit.path("total").asLong()).isEqualTo(1L);

        JsonNode miss = data("/admin/audit-logs?username=nobody-here", admin);
        assertThat(miss.path("total").asLong()).isZero();
        assertThat(miss.path("list").size()).isZero();
    }

    @Test
    @DisplayName("按时间范围过滤：明天的区间查不到今天的记录")
    void shouldFilterByDateRange() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", "AUDIT-CASE-3", "name", "Audit Range Project"));

        LocalDate today = LocalDate.now();
        assertThat(data("/admin/audit-logs?from=" + today + "&to=" + today, admin).path("total").asLong())
                .isEqualTo(1L);
        assertThat(data("/admin/audit-logs?from=" + today.plusDays(1), admin).path("total").asLong())
                .isZero();
    }

    @Test
    @DisplayName("动作品类下拉：返回已出现过的动作")
    void shouldListActions() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", "AUDIT-CASE-4", "name", "Audit Action Project"));

        JsonNode actions = data("/admin/audit-logs/actions", admin);
        assertThat(actions.isArray()).isTrue();
        assertThat(actions).extracting(node -> node.asText()).contains("PROJECT_CREATE");
    }

    @Test
    @DisplayName("考生无权查看审计记录")
    void shouldRejectNonAdmin() {
        String student = wechatToken("audit-case-student", "审计越权学生");
        String code = errorCode(HttpMethod.GET, "/admin/audit-logs", student, null);
        assertThat(code).as("审计记录含操作人与 IP，不能对非管理员开放").isEqualTo("AUTH_FORBIDDEN");
    }
}
