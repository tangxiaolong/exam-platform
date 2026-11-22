package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 练习答案/解析按业务配置展示（WBS 4.2 验收用例）。
 *
 * <p>practice.analysis-visible=false 时：判分照常，但不返回标准答案与解析；
 * 改回 true 后恢复展示——证明这条配置真的驱动了返回内容，而非只落库不生效。</p>
 */
@DisplayName("练习解析展示配置")
class SystemConfigPracticeTest extends ApiTestSupport {

    @Test
    @DisplayName("关闭解析后判分照常但不显示答案")
    void analysisSwitchShouldHideAnswerButKeepJudgement() {
        String admin = adminToken();
        String student = wechatToken("practice-analysis-student", "练习解析考生");

        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();
        createQuestion(admin, subjectId, "解析开关测试题-1");
        createQuestion(admin, subjectId, "解析开关测试题-2");

        // 关闭解析与标准答案展示
        data(HttpMethod.PUT, "/admin/configs/practice.analysis-visible", admin, Map.of("value", "false"));

        var session = data(HttpMethod.POST, "/practice/sessions", student, Map.of(
                "subjectId", subjectId, "mode", "SEQUENCE", "limit", 2));
        long practiceId = session.path("practiceId").asLong();
        var questions = session.path("questions");
        assertThat(questions.size()).as("练习会话有题").isPositive();
        long questionId = questions.get(0).path("id").asLong();

        var hidden = data(HttpMethod.POST,
                "/practice/sessions/" + practiceId + "/answers?questionId=" + questionId,
                student, Map.of("answer", List.of("B")));
        assertThat(hidden.path("correct").asBoolean()).as("判分照常执行").isFalse();
        assertThat(hidden.path("standardAnswer").size()).as("标准答案被隐藏").isZero();
        assertThat(hidden.path("analysis").isNull() || hidden.path("analysis").asText().isBlank())
                .as("解析被隐藏").isTrue();

        // 恢复默认，验证展示恢复
        data(HttpMethod.PUT, "/admin/configs/practice.analysis-visible", admin, Map.of("value", "true"));
        long secondQuestionId = questions.get(1).path("id").asLong();
        var visible = data(HttpMethod.POST,
                "/practice/sessions/" + practiceId + "/answers?questionId=" + secondQuestionId,
                student, Map.of("answer", List.of("B")));
        assertThat(visible.path("standardAnswer").size()).as("标准答案恢复展示").isPositive();
        assertThat(visible.path("analysis").asText()).as("解析恢复展示").isNotBlank();
    }

    private void createQuestion(String admin, long subjectId, String stem) {
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", stem,
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析内容",
                "difficulty", "EASY"));
    }
}
