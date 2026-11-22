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
 * 管理端增强：试卷版本历史列表 —— 生成快照后可追溯每一版不可变试卷。
 */
@DisplayName("试卷版本历史")
class PaperSnapshotListTest extends ApiTestSupport {

    @Test
    @DisplayName("生成快照后版本列表可见且字段正确")
    void snapshotList() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
        long questionId = createQuestion(admin, subjectId);

        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "版本列表测试卷",
                "mode", "FIXED",
                "questions", List.of(Map.of("questionId", questionId, "score", 2))
        )).path("id").asLong();

        data(HttpMethod.POST, "/admin/papers/" + paperId + "/snapshots", admin, null);

        var snapshots = data("/admin/papers/" + paperId + "/snapshots", admin);
        assertThat(snapshots.size()).as("至少一个版本").isGreaterThanOrEqualTo(1);
        assertThat(snapshots.get(0).path("totalScore").asInt()).isEqualTo(2);
        assertThat(snapshots.get(0).path("contentHash").asText().length()).isEqualTo(12);
        assertThat(snapshots.get(0).path("id").asLong()).isPositive();
    }

    private long createQuestion(String admin, long subjectId) {
        var body = new HashMap<String, Object>();
        body.put("subjectId", subjectId);
        body.put("type", "SINGLE");
        body.put("stem", "版本历史测试题");
        body.put("options", List.of(Map.of("key", "A", "text", "选项A"),
                Map.of("key", "B", "text", "选项B")));
        body.put("answer", List.of("A"));
        body.put("analysis", "解析");
        body.put("difficulty", "EASY");
        return data(HttpMethod.POST, "/admin/questions", admin, body).path("id").asLong();
    }
}
