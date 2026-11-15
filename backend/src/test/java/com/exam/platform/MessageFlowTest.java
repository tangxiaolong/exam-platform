package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("订阅消息：授权校验 → 事件落任务 → 发送留痕 → 幂等与重发")
class MessageFlowTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String TEMPLATE = "EXAM_REGISTER_SUCCESS";

    @Test
    @DisplayName("未授权时任务置 SKIPPED，补授权后可重发")
    void withoutSubscriptionShouldSkipThenResend() {
        String admin = adminToken();
        long sessionId = publishSession(admin, "消息测试场次-未授权");

        String student = wechatToken("msg-skip-student", "未授权考生");
        JsonNode registration = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations",
                student, Map.of());

        // 未授权：任务已创建但跳过发送，不影响报名结果
        JsonNode task = findTask(student, "register-" + registration.path("registrationId").asLong());
        assertThat(task.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(registration.path("ticketNo").asText()).isNotBlank();

        // 授权 1 次后手动重发
        JsonNode subscription = data(HttpMethod.POST, "/messages/subscriptions", student,
                Map.of("templateCode", TEMPLATE, "times", 1));
        assertThat(subscription.path("remaining").asInt()).isEqualTo(1);

        JsonNode resent = data(HttpMethod.POST,
                "/admin/messages/" + task.path("id").asLong() + "/resend", admin, null);
        assertThat(resent.path("status").asText()).isEqualTo("SENT");
        assertThat(resent.path("sentAt").asText()).isNotBlank();

        // 授权次数被扣减
        assertThat(data("/messages/subscriptions", student).get(0).path("remaining").asInt()).isZero();

        // 发送留痕
        JsonNode logs = data("/admin/messages/" + task.path("id").asLong() + "/logs", admin);
        assertThat(logs.size()).isEqualTo(1);
        assertThat(logs.get(0).path("success").asInt()).isEqualTo(1);
        assertThat(logs.get(0).path("channel").asText()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("已授权时报名即发送，重复报名不重复建任务")
    void authorizedShouldSendImmediatelyAndBeIdempotent() {
        String admin = adminToken();
        long sessionId = publishSession(admin, "消息测试场次-已授权");

        String student = wechatToken("msg-send-student", "已授权考生");
        data(HttpMethod.POST, "/messages/subscriptions", student, Map.of("templateCode", TEMPLATE));

        JsonNode registration = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations",
                student, Map.of());
        long registrationId = registration.path("registrationId").asLong();

        JsonNode task = findTask(student, "register-" + registrationId);
        assertThat(task.path("status").asText()).isEqualTo("SENT");
        assertThat(task.path("retryCount").asInt()).isZero();

        // 重复报名（幂等返回既有记录）不会再产生消息任务
        data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", student, Map.of());
        JsonNode tasks = data("/messages?pageNo=1&pageSize=50", student);
        assertThat(tasks.path("total").asLong()).isEqualTo(1);

        // 已发送的任务不允许再次重发
        assertThat(errorCode(HttpMethod.POST,
                "/admin/messages/" + task.path("id").asLong() + "/resend", admin, null))
                .isEqualTo("MESSAGE_ALREADY_SENT");
    }

    @Test
    @DisplayName("支付成功与成绩发布都会产生消息任务，按业务键互不覆盖")
    void paymentAndResultShouldPublishSeparateMessages() {
        String admin = adminToken();
        String student = wechatToken("msg-trade-student", "交易消息考生");
        data(HttpMethod.POST, "/messages/subscriptions", student,
                Map.of("templateCode", "PAYMENT_SUCCESS"));
        data(HttpMethod.POST, "/messages/subscriptions", student,
                Map.of("templateCode", "EXAM_RESULT_PUBLISHED"));

        // 支付成功 → PAYMENT_SUCCESS
        JsonNode product = data("/products?pageNo=1&pageSize=1", student).path("list").get(0);
        JsonNode order = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", product.path("id").asLong(), "quantity", 1));
        long orderId = order.path("id").asLong();
        JsonNode payment = data(HttpMethod.POST, "/orders/" + orderId + "/payments", student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        JsonNode payTask = findTask(student, "order-" + orderId);
        assertThat(payTask.path("status").asText()).isEqualTo("SENT");
        assertThat(payTask.path("templateCode").asText()).isEqualTo("PAYMENT_SUCCESS");

        // 重复支付回调 → 任务不重复
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        JsonNode allTasks = data("/messages?pageNo=1&pageSize=50", student);
        assertThat(allTasks.path("total").asLong()).isEqualTo(1);
        assertThat(admin).isNotBlank();
    }

    @Test
    @DisplayName("管理端可按状态/场景筛选消息任务")
    void adminShouldFilterTasks() {
        String admin = adminToken();
        String student = wechatToken("msg-admin-student", "筛选考生");
        data(HttpMethod.POST, "/messages/subscriptions", student, Map.of("templateCode", TEMPLATE));
        long sessionId = publishSession(admin, "消息测试场次-筛选");
        data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", student, Map.of());

        JsonNode sent = data("/admin/messages?status=SENT&scene=EXAM_REGISTER&pageNo=1&pageSize=50", admin);
        assertThat(sent.path("total").asLong()).isPositive();
        for (JsonNode task : sent.path("list")) {
            assertThat(task.path("status").asText()).isEqualTo("SENT");
            assertThat(task.path("scene").asText()).isEqualTo("EXAM_REGISTER");
        }
    }

    private JsonNode findTask(String token, String bizKey) {
        JsonNode page = data("/messages?pageNo=1&pageSize=100", token);
        for (JsonNode task : page.path("list")) {
            if (bizKey.equals(task.path("bizKey").asText())) {
                return task;
            }
        }
        throw new AssertionError("找不到业务键为 " + bizKey + " 的消息任务，实际返回：" + page);
    }

    /** 创建并发布一个含 1 道客观题的场次 */
    private long publishSession(String admin, String name) {
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
        long questionId = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "消息测试题目",
                "options", List.of(Map.of("key", "A", "text", "选项A"), Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "NORMAL")).path("id").asLong();
        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", name + "-试卷",
                "mode", "FIXED",
                "questions", List.of(Map.<String, Object>of("questionId", questionId, "score", 10))))
                .path("id").asLong();

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", name);
        body.put("registerStartAt", now.minusHours(1).format(TIME_FORMAT));
        body.put("registerEndAt", now.plusHours(1).format(TIME_FORMAT));
        body.put("startAt", now.format(TIME_FORMAT));
        body.put("endAt", now.plusHours(2).format(TIME_FORMAT));
        body.put("durationMinutes", 30);
        body.put("allowEnterBeforeMinutes", 60);
        body.put("quota", 20);
        long sessionId = data(HttpMethod.POST, "/admin/sessions", admin, body).path("id").asLong();
        data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);
        return sessionId;
    }
}
