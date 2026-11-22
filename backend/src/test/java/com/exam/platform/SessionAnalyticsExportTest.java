package com.exam.platform;

import com.exam.platform.modules.report.ExportService;
import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成绩分析报表导出。
 *
 * <p>走完整的异步任务链路：提交 → 轮询状态 → 读产物。下载的 HTTP 层（Content-Disposition、
 * 中文文件名）由既有导出下载接口覆盖，这里直接读 {@code exportService.download} 的产物，
 * 验证的是「CSV 内容是否真的包含三段」。</p>
 */
@DisplayName("成绩分析报表导出")
class SessionAnalyticsExportTest extends ApiTestSupport {

    @Autowired
    private ExportService exportService;

    @Test
    @DisplayName("导出的 CSV 包含概览、分布、逐题三段")
    void exportShouldContainAllSections() throws Exception {
        String admin = adminToken();
        long sessionId = 930000L + (System.nanoTime() % 100000);
        insertResultWithDetail(sessionId, 1L, true, true);
        insertResultWithDetail(sessionId, 2L, true, false);

        var task = data(HttpMethod.POST, "/admin/exports", admin, Map.of(
                "type", "SESSION_ANALYTICS", "sessionId", sessionId));
        long taskId = task.path("id").asLong();

        String status = waitFinished(admin, taskId);
        assertThat(status).as("异步导出应在数秒内完成").isEqualTo("SUCCESS");

        String csv = new String(exportService.download(taskId).content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("参考人数").contains("分数段分布")
                .contains("逐题正确率").contains("成绩排行");
        assertThat(csv).as("逐题段应带题干与正确率").contains("题干一").contains("题干二");
        assertThat(csv).as("概览人数 = 2 份答卷").contains("参考人数,2");
    }

    @Test
    @DisplayName("缺 sessionId 被拒——不能导出整库")
    void missingSessionIdShouldBeRejected() {
        String admin = adminToken();
        assertThat(errorCode(HttpMethod.POST, "/admin/exports", admin, Map.of(
                "type", "SESSION_ANALYTICS")))
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    private String waitFinished(String admin, long taskId) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            String status = data("/admin/exports/" + taskId, admin).path("status").asText();
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            Thread.sleep(250);
        }
        return "TIMEOUT";
    }

    private void insertResultWithDetail(long sessionId, long attemptId, boolean q1Correct, boolean q2Correct) {
        jdbcTemplate.update(
                "INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, "
                        + "full_score, correct_count, question_count, scoring_version, status, revision, "
                        + "detail_json, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 1, 10.00, 20, 0, 0, 1, 'PUBLISHED', 1, ?, NOW(), NOW(), 0)",
                attemptId, sessionId, detailJson(q1Correct, q2Correct));
    }

    private String detailJson(boolean q1Correct, boolean q2Correct) {
        return "[" + detailItem(1, "题干一", q1Correct) + "," + detailItem(2, "题干二", q2Correct) + "]";
    }

    private String detailItem(long questionId, String stem, boolean correct) {
        return "{\"questionId\":" + questionId
                + ",\"type\":\"SINGLE\""
                + ",\"stem\":\"" + stem + "\""
                + ",\"options\":[]"
                + ",\"standardAnswer\":[\"A\"]"
                + ",\"yourAnswer\":[\"" + (correct ? "A" : "B") + "\"]"
                + ",\"correct\":" + correct
                + ",\"score\":5"
                + ",\"gotScore\":" + (correct ? 5 : 0)
                + ",\"analysis\":\"\""
                + ",\"remark\":\"\""
                + ",\"marked\":false}";
    }
}
