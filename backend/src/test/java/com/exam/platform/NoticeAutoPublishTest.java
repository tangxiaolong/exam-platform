package com.exam.platform;

import com.exam.platform.modules.system.NoticeService;
import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公告定时发布。
 *
 * <p>直接调 {@code publishDue()} 而不是等调度器触发：60 秒的固定延迟放进测试里
 * 只会让用例变慢且不稳定，要验证的是「哪些草稿该被发布」这套判定本身。</p>
 */
@DisplayName("公告定时发布")
class NoticeAutoPublishTest extends ApiTestSupport {

    @Autowired
    private NoticeService noticeService;

    @Test
    @DisplayName("到点的草稿自动发布，未到点与未定生效时间的不动")
    void publishDueShouldOnlyTouchDueDrafts() {
        String admin = adminToken();

        long dueId = data(HttpMethod.POST, "/admin/notices", admin, Map.of(
                "title", "到点公告", "content", "正文", "publishAt", "2020-01-01T00:00:00")).asLong();
        long futureId = data(HttpMethod.POST, "/admin/notices", admin, Map.of(
                "title", "未来公告", "content", "正文", "publishAt", "2999-01-01T00:00:00")).asLong();
        long noDateId = data(HttpMethod.POST, "/admin/notices", admin, Map.of(
                "title", "未定公告", "content", "正文")).asLong();

        noticeService.publishDue();

        assertThat(status(dueId)).as("到点的草稿应自动发布").isEqualTo("PUBLISHED");
        assertThat(status(futureId)).as("未来的草稿不能被提前发布").isEqualTo("DRAFT");
        assertThat(status(noDateId))
                .as("没填生效时间的草稿保持草稿——它需要人工点发布")
                .isEqualTo("DRAFT");
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM cms_notice WHERE id = ?", String.class, id);
    }
}
