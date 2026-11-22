package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按知识点范围组卷。
 *
 * <p>这一条链路的三个环节缺一不可：题目标注知识点 → 规则限定知识点 → 抽题按知识点过滤。
 * 任何一环没接上，结果都是「抽不到题」，而报错看起来完全一样。</p>
 */
@DisplayName("组卷：知识点范围抽题")
class KnowledgePointPaperTest extends ApiTestSupport {

    @Test
    @DisplayName("限定知识点后只抽该知识点下的题")
    void ruleShouldOnlyPickQuestionsOfGivenKnowledgePoint() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long knowledgePointId = createKnowledgePoint(subjectId, "知识点-A");

        Set<Long> tagged = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            tagged.add(createQuestion(admin, subjectId, knowledgePointId));
        }
        // 同样的题型与难度，但未标注知识点：不应该被抽中
        for (int i = 0; i < 3; i++) {
            createQuestion(admin, subjectId, null);
        }

        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "知识点组卷",
                "mode", "RULE",
                "rules", List.of(Map.of(
                        "type", "SINGLE", "count", 2, "score", 5,
                        "knowledgePointId", knowledgePointId))))
                .path("id").asLong();

        JsonNode preview = data(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);
        assertThat(preview.path("questions").size()).isEqualTo(2);
        for (JsonNode question : preview.path("questions")) {
            assertThat(tagged).as("抽到的题必须来自指定知识点")
                    .contains(question.path("questionId").asLong());
        }
    }

    @Test
    @DisplayName("知识点下题量不足时按可读原因拒绝，而不是静默少抽")
    void insufficientInKnowledgePointShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long knowledgePointId = createKnowledgePoint(subjectId, "知识点-B");
        createQuestion(admin, subjectId, knowledgePointId);
        // 大量未标注题：证明「题库有题」不代表「该知识点有题」
        for (int i = 0; i < 3; i++) {
            createQuestion(admin, subjectId, null);
        }

        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "知识点题量不足",
                "mode", "RULE",
                "rules", List.of(Map.of(
                        "type", "SINGLE", "count", 3, "score", 5,
                        "knowledgePointId", knowledgePointId))))
                .path("id").asLong();

        String message = errorMessage(HttpMethod.POST, "/admin/papers/" + paperId + "/preview", admin, null);
        // 提示必须指向「标注知识点」：只说「可用 0 道」会让人去加题，而问题依旧
        assertThat(message).contains("知识点").contains("标注");
    }

    @Test
    @DisplayName("同题型同难度但知识点不同的两条规则不算重复")
    void differentKnowledgePointsAreNotDuplicateRules() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        long first = createKnowledgePoint(subjectId, "知识点-C");
        long second = createKnowledgePoint(subjectId, "知识点-D");

        JsonNode created = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "双知识点组卷",
                "mode", "RULE",
                "rules", List.of(
                        Map.of("type", "SINGLE", "count", 1, "score", 5, "knowledgePointId", first),
                        Map.of("type", "SINGLE", "count", 1, "score", 5, "knowledgePointId", second))));
        assertThat(created.path("mode").asText()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("开关打开时，未标注知识点的题目不允许启用")
    void requiredSwitchShouldBlockUnlabeledQuestion() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        long questionId = createQuestionWithStatus(admin, subjectId, "DISABLED");

        // 开关关闭：允许启用
        setSwitch(admin, "false");
        data(HttpMethod.PUT, "/admin/questions/" + questionId + "/status?status=ENABLED", admin, null);
        data(HttpMethod.PUT, "/admin/questions/" + questionId + "/status?status=DISABLED", admin, null);

        // 开关打开：拒绝启用未标注题目，并给出可操作的提示
        setSwitch(admin, "true");
        assertThat(errorCode(HttpMethod.PUT,
                "/admin/questions/" + questionId + "/status?status=ENABLED", admin, null))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 标注知识点后可以启用
        long knowledgePointId = createKnowledgePoint(subjectId, "知识点-E");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", "标注后可启用的题");
        body.put("options", List.of(Map.of("key", "A", "text", "选项 A"),
                Map.of("key", "B", "text", "选项 B")));
        body.put("answer", List.of("A"));
        body.put("difficulty", "NORMAL");
        body.put("knowledgePointId", knowledgePointId);
        data(HttpMethod.PUT, "/admin/questions/" + questionId, admin, body);
        data(HttpMethod.PUT, "/admin/questions/" + questionId + "/status?status=ENABLED", admin, null);

        setSwitch(admin, "false");
    }

    @Test
    @DisplayName("题目不能绑定不存在或属于其他科目的知识点")
    void questionShouldRejectForeignKnowledgePoint() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        long knowledgePointId = createKnowledgePoint(subjectId, "归属知识点");

        // 不存在的知识点：直接拒绝，否则会变成一道永远抽不到的题
        assertThat(errorCode(HttpMethod.POST, "/admin/questions", admin,
                questionBody(subjectId, 99999999L))).isEqualTo("COMMON_BAD_REQUEST");

        // 跨科目：自己建第二个科目，不再依赖「题库里恰好有多个科目」——
        // 依赖环境的条件分支会让这条断言在单科目环境里静默不执行，用例照样显示绿色
        long otherSubjectId = data(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", projectId,
                "code", "CROSS-SUBJECT-" + System.nanoTime(),
                "name", "跨科目校验科目",
                "status", "ENABLED")).path("id").asLong();
        assertThat(errorCode(HttpMethod.POST, "/admin/questions", admin,
                questionBody(otherSubjectId, knowledgePointId)))
                .as("知识点属于科目 A，却挂在科目 B 的题上").isEqualTo("COMMON_BAD_REQUEST");
    }

    private Map<String, Object> questionBody(long subjectId, Long knowledgePointId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", "归属校验题");
        body.put("options", List.of(Map.of("key", "A", "text", "选项 A"),
                Map.of("key", "B", "text", "选项 B")));
        body.put("answer", List.of("A"));
        body.put("difficulty", "NORMAL");
        body.put("knowledgePointId", knowledgePointId);
        return body;
    }

    // ---------------------------------------------------------------- helpers

    private void setSwitch(String admin, String value) {
        data(HttpMethod.PUT, "/admin/configs/question.knowledge-point.required", admin,
                Map.of("value", value));
    }

    private long createQuestionWithStatus(String admin, long subjectId, String status) {
        return data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "未标注知识点的题",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "NORMAL",
                "status", status)).path("id").asLong();
    }

    private long firstSubjectId(String admin, long projectId) {
        JsonNode subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    /** 直接落库建知识点：目前没有管理端知识点 CRUD 接口。 */
    private long createKnowledgePoint(long subjectId, String name) {
        jdbcTemplate.update(
                "INSERT INTO exam_knowledge_point (tenant_id, subject_id, name, sort_no, "
                        + "created_at, updated_at, deleted) VALUES (1, ?, ?, 0, NOW(), NOW(), 0)",
                subjectId, name);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_knowledge_point WHERE tenant_id = 1 AND subject_id = ? "
                        + "AND name = ? ORDER BY id DESC LIMIT 1",
                Long.class, subjectId, name);
        assertThat(id).isNotNull();
        return id;
    }

    private long createQuestion(String admin, long subjectId, Long knowledgePointId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", "知识点组卷测试题");
        body.put("options", List.of(Map.of("key", "A", "text", "选项 A"),
                Map.of("key", "B", "text", "选项 B")));
        body.put("answer", List.of("A"));
        body.put("analysis", "解析");
        body.put("difficulty", "NORMAL");
        if (knowledgePointId != null) {
            body.put("knowledgePointId", knowledgePointId);
        }
        return data(HttpMethod.POST, "/admin/questions", admin, body).path("id").asLong();
    }
}
