package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题目「来源」字段（PRD 4.2 字段清单）：创建保存、更新替换，全程可在详情回读。
 */
@DisplayName("题目来源字段")
class QuestionSourceTest extends ApiTestSupport {

    @Test
    @DisplayName("创建与更新都完整保留来源")
    void sourceShouldSurviveCreateAndUpdate() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();

        long questionId = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "来源测试题",
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY",
                "source", "2024年真题")).path("id").asLong();

        assertThat(data("/admin/questions/" + questionId, admin).path("source").asText())
                .as("创建时保存来源").isEqualTo("2024年真题");

        data(HttpMethod.PUT, "/admin/questions/" + questionId, admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "来源测试题",
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY",
                "source", "教材同步练习"));

        assertThat(data("/admin/questions/" + questionId, admin).path("source").asText())
                .as("更新后来源被替换").isEqualTo("教材同步练习");

        // 列表按来源过滤：命中与未命中（来源是独立筛选项，不是只存不查）
        var filtered = data("/admin/questions?source=教材同步练习&pageNo=1&pageSize=10", admin);
        assertThat(filtered.path("list").get(0).path("id").asLong()).as("按来源命中").isEqualTo(questionId);
        assertThat(data("/admin/questions?source=不存在的来源&pageNo=1&pageSize=10", admin)
                .path("list").size()).as("未命中为空").isZero();
    }
}
