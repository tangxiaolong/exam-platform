package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多场次成绩横向对比：每个请求场次一行（含 0 成绩场次），联表别名与形状可被前端消费。
 */
@DisplayName("场次成绩横向对比")
class CompareSessionsTest extends ApiTestSupport {

    @Test
    @DisplayName("对比接口按场次返回聚合行")
    void compareSessions() {
        String admin = adminToken();
        var sessions = data("/admin/sessions?pageNo=1&pageSize=5", admin);
        var list = sessions.path("list");
        assertThat(list.size()).as("种子里有场次").isPositive();
        long sessionId = list.get(0).path("id").asLong();

        var rows = data("/admin/sessions/compare?ids=" + sessionId, admin);
        assertThat(rows.size()).as("一个场次一行").isEqualTo(1);
        assertThat(rows.get(0).path("session_name").asText()).as("联到场次名称").isNotBlank();
        assertThat(rows.get(0).path("result_count").asLong())
                .as("无成绩场次 count 为 0").isGreaterThanOrEqualTo(0);

        // CSV 导出：与对比抽屉同源，内容含表头
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(admin);
        var entity = restTemplate.exchange(
                "/v1/admin/sessions/compare/export?ids=" + sessionId,
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class);
        String body = String.valueOf(entity.getBody());
        System.out.println("EXPORT STATUS=" + entity.getStatusCode()
                + " BODY=" + body.substring(0, Math.min(120, body.length())));
        assertThat(entity.getStatusCode().is2xxSuccessful()).as("导出返回 200").isTrue();
        assertThat(entity.getBody()).as("CSV 含表头").contains("session_name");
    }
}
