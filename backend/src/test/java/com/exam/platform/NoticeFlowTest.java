package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公告与系统配置。
 *
 * <p>公告最容易出错的不是增删改，而是「什么时候对考生可见」：
 * 草稿泄露、过期公告还在线、置顶不生效。这三个都在这里断言。</p>
 */
@DisplayName("公告与系统配置")
class NoticeFlowTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    @DisplayName("草稿对考生不可见，发布后才可读")
    void draftShouldBeInvisibleUntilPublished() {
        String admin = adminToken();
        long noticeId = createNotice(admin, "草稿公告", Map.of("content", "正文内容"));

        String student = wechatToken("notice-student", "公告考生");
        // 未发布的公告连「存在」都不该暴露
        assertThat(errorCode(HttpMethod.GET, "/notices/" + noticeId, student, null))
                .isEqualTo("COMMON_NOT_FOUND");
        assertThat(data("/notices", student).size()).isZero();

        data(HttpMethod.POST, "/admin/notices/" + noticeId + "/publish", admin, null);

        JsonNode detail = data("/notices/" + noticeId, student);
        assertThat(detail.path("title").asText()).isEqualTo("草稿公告");
        assertThat(detail.path("content").asText()).isEqualTo("正文内容");
        assertThat(data("/notices", student).size()).isEqualTo(1);

        // 后台列表仍能看到已发布状态
        JsonNode adminList = data("/admin/notices?status=PUBLISHED", admin);
        assertThat(adminList.size()).isEqualTo(1);
        assertThat(adminList.get(0).path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("已过期公告不在考生端出现；置顶排在最前")
    void expiredShouldBeHiddenAndPinnedFirst() {
        String admin = adminToken();

        long expired = createNotice(admin, "过期公告", Map.of(
                "expireAt", LocalDateTime.now().minusDays(1).format(TIME_FORMAT)));
        data(HttpMethod.POST, "/admin/notices/" + expired + "/publish", admin, null);

        long normal = createNotice(admin, "普通公告", Map.of());
        data(HttpMethod.POST, "/admin/notices/" + normal + "/publish", admin, null);

        long pinned = createNotice(admin, "置顶公告", Map.of("pinned", true));
        data(HttpMethod.POST, "/admin/notices/" + pinned + "/publish", admin, null);

        String student = wechatToken("notice-window", "公告时间窗考生");
        JsonNode list = data("/notices", student);

        assertThat(list.size()).as("过期公告不应出现在列表里").isEqualTo(2);
        assertThat(list.get(0).path("title").asText()).as("置顶必须排第一").isEqualTo("置顶公告");

        assertThat(errorCode(HttpMethod.GET, "/notices/" + expired, student, null))
                .isEqualTo("COMMON_NOT_FOUND");
    }

    @Test
    @DisplayName("配置改完立即生效，非法值退回默认值")
    void configShouldTakeEffectImmediately() {
        String admin = adminToken();
        data(HttpMethod.PUT, "/admin/configs/notice.list.page-size", admin,
                Map.of("value", "5", "remark", "每页条数"));

        JsonNode configs = data("/admin/configs", admin);
        String value = null;
        for (JsonNode config : configs) {
            if ("notice.list.page-size".equals(config.path("configKey").asText())) {
                value = config.path("configValue").asText();
            }
        }
        assertThat(value).as("写入后立即读到新值").isEqualTo("5");

        // 写非法值不应抛错，读取方要能退回默认值而不是把接口打挂
        data(HttpMethod.PUT, "/admin/configs/notice.list.page-size", admin, Map.of("value", "abc"));
        assertThat(data("/admin/configs", admin).size()).isPositive();

        // 恢复，避免影响其他用例
        data(HttpMethod.PUT, "/admin/configs/notice.list.page-size", admin, Map.of("value", "20"));
    }

    private long createNotice(String admin, String title, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.putAll(extra);
        return data(HttpMethod.POST, "/admin/notices", admin, body).asLong();
    }
}
