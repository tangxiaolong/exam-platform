package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 练习收藏（PRD 4.3）：复用既有 user_favorite 收藏接口。
 *
 * <p>收藏 → ids 可见 → 列表非空 → 重复收藏幂等 → 取消 → 重复取消幂等 →
 * 再次收藏（软删复活路径）仍然成功。</p>
 */
@DisplayName("练习收藏")
class FavoriteFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("收藏生命周期与幂等")
    void favoriteLifecycle() {
        String admin = adminToken();
        String student = wechatToken("favorite-student", "收藏考生");

        long projectId = firstProjectId(admin);
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        long subjectId = subjects.get(0).path("id").asLong();
        long questionId = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "收藏测试题",
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY")).path("id").asLong();

        // 收藏 → ids 与列表都可见
        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        var ids = data("/favorites/ids", student);
        assertThat(ids.size()).isEqualTo(1);
        assertThat(ids.get(0).asLong()).isEqualTo(questionId);

        var list = data("/favorites?pageNo=1&pageSize=10", student);
        assertThat(list.size()).as("收藏列表非空").isPositive();

        // 重复收藏：幂等，不变多
        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites/ids", student).size()).as("重复收藏不变多").isEqualTo(1);

        // 取消 → 消失；重复取消不报错
        data(HttpMethod.DELETE, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites/ids", student).size()).as("取消后为空").isZero();
        data(HttpMethod.DELETE, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites/ids", student).size()).as("重复取消幂等").isZero();

        // 取消后再次收藏：软删记录复活，仍然成功
        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites/ids", student).size()).as("复活后重新可见").isEqualTo(1);
    }
}
