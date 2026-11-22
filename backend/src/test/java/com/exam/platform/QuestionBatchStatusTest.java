package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题库批量启停：只动选中的题、返回处理条数。
 *
 * <p>未选中的第三题保持原状态——批量操作的经典事故是把「全选」的误操作
 * 变成灾难，这里断言边界：选了两道，就只动两道。</p>
 */
@DisplayName("题库批量启停")
class QuestionBatchStatusTest extends ApiTestSupport {

    @Test
    @DisplayName("批量停用只影响选中的题")
    void batchDisableShouldOnlyTouchSelected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        long q1 = createQuestion(admin, subjectId, "批量测试题-1");
        long q2 = createQuestion(admin, subjectId, "批量测试题-2");
        long q3 = createQuestion(admin, subjectId, "批量测试题-3");

        var changed = data(HttpMethod.PUT, "/admin/questions/batch-status", admin,
                Map.of("ids", List.of(q1, q2), "status", "DISABLED"));
        assertThat(changed.asInt()).as("返回实际处理条数").isEqualTo(2);

        assertThat(data("/admin/questions/" + q1, admin).path("status").asText()).isEqualTo("DISABLED");
        assertThat(data("/admin/questions/" + q2, admin).path("status").asText()).isEqualTo("DISABLED");
        assertThat(data("/admin/questions/" + q3, admin).path("status").asText())
                .as("未选中的题不受影响").isNotEqualTo("DISABLED");
    }

    private long firstSubjectId(String admin, long projectId) {
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    private long createQuestion(String admin, long subjectId, String stem) {
        return data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", stem,
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY")).path("id").asLong();
    }
}
