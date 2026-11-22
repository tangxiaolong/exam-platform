package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组卷的难度配比。
 *
 * <p>配比算法最容易错的地方是「各难度题数之和与 count 对不上」——
 * 四舍五入必然产生差值，差值的归属决定了每次抽出来的卷子题数是否稳定。
 * 这里断言的就是这个总和，而不是某种具体的取整细节。</p>
 */
@DisplayName("组卷：难度配比")
class DifficultyMixPaperTest extends ApiTestSupport {

    @Test
    @DisplayName("权重恰好整除时，各难度题数与配比一致")
    void mixShouldMatchExactlyWhenDivisible() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestions(admin, subjectId, "EASY", 3);
        createQuestions(admin, subjectId, "NORMAL", 5);
        createQuestions(admin, subjectId, "HARD", 3);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "SINGLE");
        rule.put("count", 6);
        rule.put("score", 5);
        rule.put("difficultyMix", Map.of("EASY", 1, "NORMAL", 2, "HARD", 3));

        long paperId = createPaper(admin, projectId, subjectId, List.of(rule));
        JsonNode preview = data(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);

        assertThat(preview.path("questions").size()).isEqualTo(6);
        assertThat(difficultyCounts(preview)).containsEntry("EASY", 1L)
                .containsEntry("NORMAL", 2L).containsEntry("HARD", 3L);
    }

    @Test
    @DisplayName("除不尽时题数总和仍等于 count，差值补给 NORMAL")
    void roundingGapShouldGoToNormal() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestions(admin, subjectId, "EASY", 4);
        createQuestions(admin, subjectId, "NORMAL", 4);
        createQuestions(admin, subjectId, "HARD", 4);

        // 7 / 3 各得 2.33 → 四舍五入 2+2+2=6，差 1 道补给 NORMAL
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "SINGLE");
        rule.put("count", 7);
        rule.put("score", 5);
        rule.put("difficultyMix", Map.of("EASY", 1, "NORMAL", 1, "HARD", 1));

        long paperId = createPaper(admin, projectId, subjectId, List.of(rule));
        JsonNode preview = data(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);

        assertThat(preview.path("questions").size()).as("题数总和必须等于 count").isEqualTo(7);
        assertThat(difficultyCounts(preview)).containsEntry("EASY", 2L)
                .containsEntry("NORMAL", 3L).containsEntry("HARD", 2L);
    }

    @Test
    @DisplayName("difficulty 与 difficultyMix 同时给出时被拒绝")
    void difficultyAndMixTogetherShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "SINGLE");
        rule.put("count", 2);
        rule.put("score", 5);
        rule.put("difficulty", "EASY");
        rule.put("difficultyMix", Map.of("NORMAL", 2));

        assertThat(errorCode(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "冲突配比",
                "mode", "RULE",
                "rules", List.of(rule)))).isEqualTo("COMMON_BAD_REQUEST");
    }

    // ---------------------------------------------------------------- helpers

    /** 用题目 ID 反查难度分布：preview 只返回 questionId 与分值。 */
    private Map<String, Long> difficultyCounts(JsonNode preview) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode question : preview.path("questions")) {
            ids.add(question.path("questionId").asLong());
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Long id : ids) {
            String difficulty = jdbcTemplate.queryForObject(
                    "SELECT difficulty FROM qb_question WHERE id = ?", String.class, id);
            counts.merge(difficulty, 1L, Long::sum);
        }
        return counts;
    }

    private long firstSubjectId(String admin, long projectId) {
        JsonNode subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    private long createPaper(String admin, long projectId, long subjectId, List<Map<String, Object>> rules) {
        return data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "配比组卷",
                "mode", "RULE",
                "rules", rules)).path("id").asLong();
    }

    private void createQuestions(String admin, long subjectId, String difficulty, int count) {
        for (int i = 0; i < count; i++) {
            data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                    "subjectId", subjectId,
                    "type", "SINGLE",
                    "stem", "配比测试题-" + difficulty + "-" + i,
                    "options", List.of(Map.of("key", "A", "text", "选项 A"),
                            Map.of("key", "B", "text", "选项 B")),
                    "answer", List.of("A"),
                    "analysis", "解析",
                    "difficulty", difficulty));
        }
    }
}
