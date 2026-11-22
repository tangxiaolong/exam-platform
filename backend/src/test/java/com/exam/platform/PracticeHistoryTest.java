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
 * 学习记录：本人练习会话分页列表（科目名、进度、状态可见）。
 */
@DisplayName("学习记录（练习会话列表）")
class PracticeHistoryTest extends ApiTestSupport {

    @Test
    @DisplayName("本人练习会话分页返回进度与科目名")
    void practiceHistory() {
        String admin = adminToken();
        String student = wechatToken("practice-history-student", "记录考生");
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();
        createQuestion(admin, subjectId, "记录测试题");

        var created = data(HttpMethod.POST, "/practice/sessions", student, Map.of(
                "subjectId", subjectId, "mode", "SEQUENCE", "limit", 3));
        long practiceId = created.path("practiceId").asLong();

        var rows = data("/practice/sessions?pageNo=1&pageSize=10", student);
        var list = rows.path("list");
        assertThat(list.size()).as("至少一条记录").isGreaterThanOrEqualTo(1);
        var row = list.get(0);
        assertThat(row.path("practice_id").asLong()).as("最新一条即刚创建的会话").isEqualTo(practiceId);
        assertThat(row.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(row.path("subject_name").asText()).as("科目名已补全").isNotBlank();
        assertThat(row.path("total_count").asInt()).isPositive();
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
