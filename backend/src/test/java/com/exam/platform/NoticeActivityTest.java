package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD 3.1 首页「活动」：活动复用公告模型（type=ACTIVITY），
 * 考生端按类型过滤可见、默认查询保持全量（向后兼容）。
 */
@DisplayName("首页活动（公告类型）")
class NoticeActivityTest extends ApiTestSupport {

    @Test
    @DisplayName("活动公告按类型过滤可见，默认查询不过滤")
    void activityNotice() {
        String admin = adminToken();
        String student = wechatToken("activity-student", "活动考生");

        long id = data(HttpMethod.POST, "/admin/notices", admin, Map.of(
                "title", "双十一活动", "content", "活动内容：报名费五折", "type", "ACTIVITY"
        )).asLong();
        data(HttpMethod.POST, "/admin/notices/" + id + "/publish", admin, null);

        var activities = data("/notices?type=ACTIVITY&pageNo=1&pageSize=20", student);
        boolean found = false;
        for (var row : activities) {
            if (row.path("id").asLong() == id) {
                found = true;
                assertThat(row.path("type").asText()).isEqualTo("ACTIVITY");
            }
        }
        assertThat(found).as("活动公告可被考生端看到").isTrue();

        var noticesOnly = data("/notices?type=NOTICE&pageNo=1&pageSize=20", student);
        boolean leaked = false;
        for (var row : noticesOnly) {
            if (row.path("id").asLong() == id) {
                leaked = true;
            }
        }
        assertThat(leaked).as("type=NOTICE 过滤不混入活动公告").isFalse();

        var all = data("/notices?pageNo=1&pageSize=20", student);
        boolean inAll = false;
        for (var row : all) {
            if (row.path("id").asLong() == id) {
                inAll = true;
            }
        }
        assertThat(inAll).as("默认查询不过滤类型（全量口径）").isTrue();
    }
}
