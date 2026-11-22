package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节练习（按知识点练习）：knowledgePointId 非空时只取该知识点下的启用题，
 * 同科目不挂该知识点的题不得混入。
 */
@DisplayName("按知识点练习（章节练习）")
class PracticeKnowledgePointTest extends ApiTestSupport {

    @Test
    @DisplayName("章节练习只取该知识点下的题")
    void practiceByKnowledgePoint() {
        String admin = adminToken();
        String student = wechatToken("kp-practice-student", "章节练习考生");
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();

        long kpId = data(HttpMethod.POST, "/admin/knowledge-points", admin, Map.of(
                "subjectId", subjectId, "name", "章节练习知识点")).path("id").asLong();
        createQuestion(admin, subjectId, "章节题-1", kpId);
        createQuestion(admin, subjectId, "章节题-2", kpId);
        createQuestion(admin, subjectId, "干扰题（无知识点）", null);

        var practice = data(HttpMethod.POST, "/practice/sessions", student, Map.of(
                "subjectId", subjectId, "mode", "SEQUENCE", "limit", 10,
                "knowledgePointId", kpId));
        assertThat(practice.path("totalCount").asInt())
                .as("只取该知识点下的两道题").isEqualTo(2);
    }

    private void createQuestion(String admin, long subjectId, String stem, Long knowledgePointId) {
        var body = new HashMap<String, Object>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", stem);
        body.put("options", List.of(Map.of("key", "A", "text", "选项A"),
                Map.of("key", "B", "text", "选项B")));
        body.put("answer", List.of("A"));
        body.put("analysis", "解析");
        body.put("difficulty", "EASY");
        if (knowledgePointId != null) {
            body.put("knowledgePointId", knowledgePointId);
        }
        data(HttpMethod.POST, "/admin/questions", admin, body);
    }
}
