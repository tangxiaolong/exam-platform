package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站内消息（PRD 4.10）：群发 → 收件箱可见 → 已读标记。
 *
 * <p>不依赖订阅授权——「系统维护通知」这类消息不能因为用户没点「允许通知」就失联。</p>
 */
@DisplayName("站内消息")
class InboxFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("群发后考生可见且可标记已读")
    void inboxSendAndRead() {
        String admin = adminToken();
        String student = wechatToken("inbox-student", "收件考生");

        // 群发（userIds 为空 = 本租户全体非管理员账号）
        var reached = data(HttpMethod.POST, "/admin/inbox", admin, Map.of(
                "title", "站内信测试",
                "content", "今晚 22:00 系统维护，请提前保存答案"));
        assertThat(reached.asInt()).as("触达人数大于零").isPositive();

        // 管理端发送历史可见：第一条即本次群发落下的收件记录
        var history = data("/admin/inbox/history?pageNo=1&pageSize=10", admin);
        var record = history.path("list").get(0);
        assertThat(record.path("title").asText()).as("历史可见本次群发").isEqualTo("站内信测试");
        assertThat(record.path("userId").asLong()).isPositive();

        // 收件箱可见：未读 1 条，标题匹配
        assertThat(data("/inbox/unread-count", student).asLong()).as("一条未读").isEqualTo(1);
        var page = data("/inbox?pageNo=1&pageSize=10", student);
        assertThat(page.path("list").size()).isPositive();
        var first = page.path("list").get(0);
        assertThat(first.path("title").asText()).isEqualTo("站内信测试");
        assertThat(first.path("read").asBoolean()).as("初始未读").isFalse();

        // 标记已读：未读数清零，列表状态翻转
        long messageId = first.path("id").asLong();
        data(HttpMethod.POST, "/inbox/" + messageId + "/read", student, null);
        assertThat(data("/inbox/unread-count", student).asLong()).as("已读后清零").isZero();
        assertThat(data("/inbox?pageNo=1&pageSize=10", student)
                .path("list").get(0).path("read").asBoolean()).as("列表状态已翻转").isTrue();

        // 指定收件人投送：userIds 路径只落指定账号（不再广播全体）
        var me = data("/users/me", student);
        long myUserId = me.path("id").asLong();
        data(HttpMethod.POST, "/admin/inbox", admin, Map.of(
                "userIds", List.of(myUserId),
                "title", "指定投送测试",
                "content", "仅发给你一个账号"));
        assertThat(data("/inbox/unread-count", student).asLong()).as("指定投送 +1 未读").isEqualTo(1);
        assertThat(data("/inbox?pageNo=1&pageSize=10", student)
                .path("list").get(0).path("title").asText()).as("最新一条是指定投送")
                .isEqualTo("指定投送测试");

        // 一键全部已读：清掉唯一一条未读，未读数归零
        int cleared = data(HttpMethod.POST, "/inbox/read-all", student, null).asInt();
        assertThat(cleared).as("清掉 1 条未读").isEqualTo(1);
        assertThat(data("/inbox/unread-count", student).asLong()).as("一键已读后清零").isZero();

        // 批次聚合视图：指定投送成为独立一批（1 人触达、1 人已读），群发批次也在列
        var batches = data("/admin/inbox/batches?pageNo=1&pageSize=10", admin);
        var batchList = batches.path("list");
        assertThat(batchList.size()).as("至少两个批次").isGreaterThanOrEqualTo(2);
        boolean foundBatch = false;
        for (var row : batchList) {
            if ("指定投送测试".equals(row.path("title").asText())) {
                assertThat(row.path("total").asLong()).as("批次触达 1 人").isEqualTo(1);
                assertThat(row.path("read_count").asLong()).as("批次已读 1 人").isEqualTo(1);
                foundBatch = true;
            }
        }
        assertThat(foundBatch).as("指定投送批次可见").isTrue();
    }
}
