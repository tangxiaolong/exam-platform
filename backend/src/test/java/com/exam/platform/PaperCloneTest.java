package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 试卷复制：沿用组卷规则生成新草稿（版本 1、总分一致）。
 */
@DisplayName("试卷复制")
class PaperCloneTest extends ApiTestSupport {

    @Test
    @DisplayName("复制沿用规则并生成新草稿")
    void cloneShouldCopyRulesAndCreateDraft() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId, "复制卷测试题-1");
        createQuestion(admin, subjectId, "复制卷测试题-2");

        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "源试卷",
                "mode", "RULE",
                "rules", List.of(Map.of("type", "SINGLE", "count", 2, "score", 5))))
                .path("id").asLong();

        var cloned = data(HttpMethod.POST, "/admin/papers/" + paperId + "/clone?name=复制出的卷", admin, null);

        long newId = cloned.path("id").asLong();
        assertThat(newId).as("是新试卷").isNotEqualTo(paperId);
        assertThat(cloned.path("name").asText()).isEqualTo("复制出的卷");
        assertThat(cloned.path("mode").asText()).isEqualTo("RULE");
        assertThat(cloned.path("totalScore").asInt()).as("沿用总分").isEqualTo(10);
        assertThat(cloned.path("status").asText()).as("生成为草稿").isEqualTo("DRAFT");
        assertThat(cloned.path("version").asInt()).as("新卷版本从 1 起").isEqualTo(1);

        var rules = data("/admin/papers/" + newId + "/rules", admin);
        assertThat(rules.size()).as("规则行一并复制").isEqualTo(1);
        assertThat(rules.get(0).path("count").asInt()).isEqualTo(2);
        assertThat(rules.get(0).path("score").asInt()).isEqualTo(5);
    }

    private long firstSubjectId(String admin, long projectId) {
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    private void createQuestion(String admin, long subjectId, String stem) {
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", stem,
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY"));
    }
}
