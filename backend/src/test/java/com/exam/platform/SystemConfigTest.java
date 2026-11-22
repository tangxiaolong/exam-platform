package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统参数管理：改值 → 立即生效（读缓存同步失效）→ 恢复。
 *
 * <p>用「题目必须标注知识点」开关做行为验证：开关置 true 后启用未标注题目被拒，
 * 置回 false 后恢复可用——证明参数写入不只是落库，而是真的驱动了业务规则。</p>
 */
@DisplayName("系统参数管理")
class SystemConfigTest extends ApiTestSupport {

    @Test
    @DisplayName("知识点开关即时生效且可恢复")
    void knowledgePointSwitchShouldTakeEffectImmediately() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();

        long questionId = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "参数开关测试题",
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY")).path("id").asLong();

        // 开关打开：启用未标注知识点的题被拒
        data(HttpMethod.PUT, "/admin/configs/question.knowledge-point.required", admin, Map.of("value", "true"));
        assertThat(errorCode(HttpMethod.PUT,
                "/admin/questions/" + questionId + "/status?status=ENABLED", admin, null))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 开关关闭：同一道题可以启用（证明缓存已失效、规则即时恢复）
        data(HttpMethod.PUT, "/admin/configs/question.knowledge-point.required", admin, Map.of("value", "false"));
        data(HttpMethod.PUT, "/admin/questions/" + questionId + "/status?status=ENABLED", admin, null);
        assertThat(data("/admin/questions/" + questionId, admin).path("status").asText())
                .isEqualTo("ENABLED");
    }
}
