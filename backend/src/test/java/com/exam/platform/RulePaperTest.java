package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("规则组卷：按题型抽题 → 试组卷校验 → 发布生成快照")
class RulePaperTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    @DisplayName("按规则抽题：题型数量正确、无重复题、总分符合规则")
    void previewShouldGenerateByRules() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        // 历史题库中可能已有单/多选，这里只保证“够用”
        for (int i = 0; i < 4; i++) {
            createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        }
        for (int i = 0; i < 2; i++) {
            createQuestion(admin, subjectId, "MULTIPLE", List.of("A", "B", "C"), List.of("A", "C"));
        }

        long paperId = createRulePaper(admin, projectId, subjectId, List.of(
                Map.<String, Object>of("type", "SINGLE", "count", 3, "score", 10),
                Map.<String, Object>of("type", "MULTIPLE", "count", 1, "score", 20)));

        JsonNode preview = data(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);
        assertThat(preview.path("totalScore").asInt()).isEqualTo(50);
        assertThat(preview.path("questions").size()).isEqualTo(4);

        Set<Long> ids = new HashSet<>();
        Map<String, Integer> typeCount = new LinkedHashMap<>();
        for (JsonNode question : preview.path("questions")) {
            ids.add(question.path("questionId").asLong());
            typeCount.merge(question.path("type").asText(), 1, Integer::sum);
        }
        assertThat(ids).as("同一份试卷不得出现重复题目").hasSize(4);
        assertThat(typeCount.get("SINGLE")).isEqualTo(3);
        assertThat(typeCount.get("MULTIPLE")).isEqualTo(1);

        // 两次试组卷抽到的可能是不同题目，但都必须满足规则
        JsonNode second = data(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);
        assertThat(second.path("questions").size()).isEqualTo(4);
        assertThat(second.path("totalScore").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("题库不足时试组卷给出可读错误，且不产生快照")
    void insufficientQuestionsShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long paperId = createRulePaper(admin, projectId, subjectId, List.of(
                Map.<String, Object>of("type", "SINGLE", "count", 999, "score", 10)));

        String code = errorCode(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);
        assertThat(code).isEqualTo("EXAM_PAPER_RULE_INSUFFICIENT");

        // 发布同样被拒绝
        assertThat(errorCode(HttpMethod.POST, "/admin/papers/" + paperId + "/snapshots", admin, null))
                .isEqualTo("EXAM_PAPER_RULE_INSUFFICIENT");
    }

    @Test
    @DisplayName("重复规则被拒绝")
    void duplicateRulesShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        String code = errorCode(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "违规规则试卷",
                "mode", "RULE",
                "rules", List.of(
                        Map.of("type", "SINGLE", "count", 1, "score", 5),
                        Map.of("type", "SINGLE", "count", 2, "score", 5))));
        assertThat(code).isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("规则试卷发布场次后，快照题目与规则一致且之后不再变化")
    void publishShouldSnapshotGeneratedQuestions() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        for (int i = 0; i < 3; i++) {
            createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        }

        long paperId = createRulePaper(admin, projectId, subjectId, List.of(
                Map.<String, Object>of("type", "SINGLE", "count", 2, "score", 15)));
        long sessionId = createSession(admin, projectId, paperId);
        JsonNode published = data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);

        JsonNode snapshot = data("/admin/papers/snapshots/" + published.path("snapshotId").asLong(), admin);
        assertThat(snapshot.path("questions").size()).isEqualTo(2);
        assertThat(snapshot.path("totalScore").asInt()).isEqualTo(30);

        // 新增题目不影响已生成快照
        createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        JsonNode again = data("/admin/papers/snapshots/" + published.path("snapshotId").asLong(), admin);
        assertThat(again.path("questions").size()).isEqualTo(2);
    }

    private long firstSubjectId(String admin, long projectId) {
        JsonNode subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    private long createRulePaper(String admin, long projectId, long subjectId, List<Map<String, Object>> rules) {
        JsonNode created = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "规则组卷试卷",
                "mode", "RULE",
                "rules", rules));
        assertThat(created.path("mode").asText()).isEqualTo("RULE");
        return created.path("id").asLong();
    }

    private long createSession(String admin, long projectId, long paperId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", "规则组卷场次");
        body.put("registerStartAt", now.minusHours(1).format(TIME_FORMAT));
        body.put("registerEndAt", now.plusHours(1).format(TIME_FORMAT));
        body.put("startAt", now.format(TIME_FORMAT));
        body.put("endAt", now.plusHours(2).format(TIME_FORMAT));
        body.put("durationMinutes", 30);
        body.put("allowEnterBeforeMinutes", 60);
        body.put("quota", 20);
        return data(HttpMethod.POST, "/admin/sessions", admin, body).path("id").asLong();
    }

    private void createQuestion(String admin, long subjectId, String type,
                                List<String> optionKeys, List<String> answer) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String key : optionKeys) {
            options.add(Map.of("key", key, "text", "选项 " + key));
        }
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", type,
                "stem", "规则组卷测试题（" + type + "）",
                "options", options,
                "answer", answer,
                "analysis", "解析",
                "difficulty", "NORMAL"));
    }
}
