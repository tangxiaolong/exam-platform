package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 练习断点续练（PRD 4.3）：最近练习可恢复，作答进度随时可见。
 *
 * <p>无记录返回 null 而不是报错——「还没练过」是正常状态，不是错误。</p>
 */
@DisplayName("练习断点续练")
class PracticeRecentTest extends ApiTestSupport {

    @Test
    @DisplayName("最近练习可恢复且进度保留")
    void recentPractice() {
        String admin = adminToken();
        String student = wechatToken("practice-recent-student", "续练考生");

        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();
        createQuestion(admin, subjectId, "续练测试题");

        // 没练过：data 为空（null 或字段省略），不报错
        var first = data("/practice/sessions/recent?subjectId=" + subjectId, student);
        assertThat(first.isNull() || first.isMissingNode()).as("无记录返回空").isTrue();

        // 创建练习后，recent 应恢复同一会话、进度为 0
        var created = data(HttpMethod.POST, "/practice/sessions", student, Map.of(
                "subjectId", subjectId, "mode", "SEQUENCE", "limit", 3));
        long practiceId = created.path("practiceId").asLong();
        var recent = data("/practice/sessions/recent?subjectId=" + subjectId, student);
        assertThat(recent.path("practiceId").asLong()).as("恢复同一会话").isEqualTo(practiceId);
        assertThat(recent.path("answeredCount").asInt()).as("初始进度为 0").isZero();

        // 答一题后，恢复出来的进度是 1——断点续练的核心断言
        long questionId = created.path("questions").get(0).path("id").asLong();
        data(HttpMethod.POST,
                "/practice/sessions/" + practiceId + "/answers?questionId=" + questionId,
                student, Map.of("answer", List.of("B")));
        assertThat(data("/practice/sessions/recent?subjectId=" + subjectId, student)
                .path("answeredCount").asInt()).as("进度已保留").isEqualTo(1);
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
