package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题库批量移动科目：一批题从科目 A 移到科目 B，详情归属与按科目查询同步生效。
 * 卷快照在发布时冻结，移动不影响历史卷面（本用例不触碰场次，验证的是移动本身）。
 */
@DisplayName("题库批量移动科目")
class BatchMoveSubjectTest extends ApiTestSupport {

    @Test
    @DisplayName("批量移动后按新科目可见")
    void batchMoveSubject() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectA = subjects.get(0).path("id").asLong();

        long subjectB = data(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", projectId,
                "code", "MOVE-SUBJECT-" + System.nanoTime(),
                "name", "移动目标科目")).path("id").asLong();

        long q1 = createQuestion(admin, subjectA, "移动题-1");
        long q2 = createQuestion(admin, subjectA, "移动题-2");

        int moved = data(HttpMethod.PUT, "/admin/questions/batch-subject", admin,
                Map.of("ids", List.of(q1, q2), "subjectId", subjectB)).asInt();
        assertThat(moved).as("两条都移动").isEqualTo(2);

        assertThat(data("/admin/questions/" + q1, admin).path("subjectId").asLong())
                .as("详情归属已切换").isEqualTo(subjectB);
        assertThat(data("/admin/questions?subjectId=" + subjectB + "&pageNo=1&pageSize=10", admin)
                .path("list").size()).as("新科目下可查").isPositive();
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
