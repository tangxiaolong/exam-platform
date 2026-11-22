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
 * 项目中心「题库」：科目列表接口回填启用题量（questionCount），供详情页展示题库规模。
 */
@DisplayName("项目中心科目题库量")
class ContentQuestionCountTest extends ApiTestSupport {

    @Test
    @DisplayName("科目列表回填启用题量")
    void subjectQuestionCount() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();
        createQuestion(admin, subjectId, "题库量测试题");

        var withCounts = data("/projects/" + projectId + "/subjects", admin);
        long found = 0;
        for (var subject : withCounts) {
            if (subject.path("id").asLong() == subjectId) {
                found = subject.path("questionCount").asLong();
            }
        }
        assertThat(found).as("新题的科目题量至少为 1").isGreaterThanOrEqualTo(1);
    }

    private void createQuestion(String admin, long subjectId, String stem) {
        var body = new HashMap<String, Object>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", stem);
        body.put("options", List.of(Map.of("key", "A", "text", "选项A"),
                Map.of("key", "B", "text", "选项B")));
        body.put("answer", List.of("A"));
        body.put("analysis", "解析");
        body.put("difficulty", "EASY");
        data(HttpMethod.POST, "/admin/questions", admin, body);
    }
}
