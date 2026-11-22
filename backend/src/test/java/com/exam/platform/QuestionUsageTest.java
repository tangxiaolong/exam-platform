package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题库使用分析。
 *
 * <p>用 admin API 建一道新题再构造答卷明细：新题的 questionId 在断言里唯一可辨认，
 * 不会和其他测试遗留的答卷数据混淆。</p>
 */
@DisplayName("题库使用分析")
class QuestionUsageTest extends ApiTestSupport {

    @Test
    @DisplayName("跨答卷汇总出现次数与答对次数")
    void analyzeShouldAggregateAcrossAttempts() {
        String admin = adminToken();
        Long subjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_subject WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);
        var created = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "使用分析题",
                "options", List.of(Map.of("label", "A", "content", "选项A"),
                        Map.of("label", "B", "content", "选项B")),
                "answer", List.of("A")));
        long questionId = created.path("id").asLong();

        // 两份答卷各含本题：一份答对、一份答错 → 出现 2 次、答对 1 次
        insertDetailOnly(item(questionId, true));
        insertDetailOnly(item(questionId, false));

        var rows = data("/admin/question-usage?days=30", admin);

        JsonNode target = null;
        for (JsonNode row : rows) {
            if (row.path("questionId").asLong() == questionId) {
                target = row;
                break;
            }
        }
        assertThat(target).as("新插入的题应出现在分析结果里").isNotNull();
        assertThat(target.path("appearedCount").asInt())
                .as("被两份答卷抽中").isEqualTo(2);
        assertThat(target.path("correctCount").asInt())
                .as("一份答对一份答错").isEqualTo(1);
    }

    private void insertDetailOnly(String detailJson) {
        long seed = System.nanoTime() % 1000000;
        jdbcTemplate.update(
                "INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, "
                        + "full_score, correct_count, question_count, scoring_version, status, revision, "
                        + "detail_json, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 1, 0, 0, 0, 0, 1, 'PUBLISHED', 1, ?, NOW(), NOW(), 0)",
                seed, 940000 + seed, detailJson);
    }

    private String item(long questionId, boolean correct) {
        return "[{\"questionId\":" + questionId
                + ",\"type\":\"SINGLE\""
                + ",\"stem\":\"使用分析题\""
                + ",\"options\":[]"
                + ",\"standardAnswer\":[\"A\"]"
                + ",\"yourAnswer\":[\"" + (correct ? "A" : "B") + "\"]"
                + ",\"correct\":" + correct
                + ",\"score\":5"
                + ",\"gotScore\":" + (correct ? 5 : 0)
                + ",\"analysis\":\"\""
                + ",\"remark\":\"\""
                + ",\"marked\":false}]";
    }
}
