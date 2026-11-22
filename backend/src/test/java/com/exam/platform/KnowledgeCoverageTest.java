package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识点题库覆盖率：无题知识点显式计 0（教研一眼能看到"哪个知识点还没题"），挂题后计 1。
 */
@DisplayName("知识点题库覆盖率")
class KnowledgeCoverageTest extends ApiTestSupport {

    @Test
    @DisplayName("无题计 0，挂题计 1")
    void coverage() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();

        long kpId = data(HttpMethod.POST, "/admin/knowledge-points", admin, Map.of(
                "subjectId", subjectId, "name", "覆盖测试知识点")).path("id").asLong();

        // 无题知识点必须出现在列表且计数为 0
        boolean foundZero = false;
        for (var row : data("/admin/knowledge-points/coverage?subjectId=" + subjectId, admin)) {
            if (row.path("knowledge_point_id").asLong() == kpId) {
                assertThat(row.path("question_count").asLong()).isZero();
                foundZero = true;
            }
        }
        assertThat(foundZero).as("无题知识点也出现").isTrue();

        // 挂一道启用题后计数为 1
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "覆盖率测试题",
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY",
                "knowledgePointId", kpId));

        boolean foundOne = false;
        for (var row : data("/admin/knowledge-points/coverage?subjectId=" + subjectId, admin)) {
            if (row.path("knowledge_point_id").asLong() == kpId) {
                assertThat(row.path("question_count").asLong()).as("挂题后计数 1").isEqualTo(1);
                foundOne = true;
            }
        }
        assertThat(foundOne).as("挂题后知识点可见").isTrue();
    }
}
