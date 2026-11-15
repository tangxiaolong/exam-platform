package com.exam.platform;

import com.exam.platform.support.PlainApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答题保存的「按用户」配额：同一用户在配额内可继续请求，超限后被拒绝并返回 Retry-After。
 *
 * <p>本类使用独立的低配额上下文，避免与其他用例的高配额上下文互相影响。
 * 请求走真实考试接口路径，但配额耗尽发生在拦截器阶段，因此不需要先造出完整考试数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 只收紧答题保存的用户维度，其余放宽，保证同 IP 的其他调用不受影响
        "app.rate-limit.default-rule.per-user.capacity=100000",
        "app.rate-limit.default-rule.per-ip.capacity=100000",
        "app.rate-limit.answer-save.per-user.capacity=2",
        "app.rate-limit.answer-save.per-user.refill-tokens=1",
        "app.rate-limit.answer-save.per-user.refill-period-ms=60000",
        "app.rate-limit.answer-save.per-ip.capacity=100000",
        "app.rate-limit.auth-login.per-user.capacity=100000",
        "app.rate-limit.auth-login.per-ip.capacity=100000"
})
@ActiveProfiles("h2")
@DisplayName("限流：答题保存按用户配额")
class AnswerSaveRateLimitTest extends PlainApiTestSupport {

    @Test
    @DisplayName("配额内放行，超限返回 RATE_LIMITED 并带 Retry-After")
    void shouldRejectAfterQuotaExhausted() throws Exception {
        String student = wechatToken("ratelimit-user", "限流考生");
        String path = "/attempts/999999/answers/999999";

        // 配额 2：前两次进入业务逻辑（返回业务错误码，而非限流码）
        String first = errorCode(HttpMethod.PUT, path, student, Map.of("answer", java.util.List.of("A")));
        String second = errorCode(HttpMethod.PUT, path, student, Map.of("answer", java.util.List.of("A")));
        assertThat(first).isNotEqualTo("RATE_LIMITED");
        assertThat(second).isNotEqualTo("RATE_LIMITED");

        // 第三次被限流拦截，且响应头给出 Retry-After
        ResponseEntity<String> rejected = exchange(HttpMethod.PUT, path, student,
                Map.of("answer", java.util.List.of("A")));
        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(rejected.getBody()).path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(Long.parseLong(rejected.getHeaders().getFirst("Retry-After"))).isPositive();
    }

    @Test
    @DisplayName("不同用户的配额互相独立")
    void quotaShouldBeIndependentPerUser() {
        String userA = wechatToken("ratelimit-user-a", "限流考生A");
        String userB = wechatToken("ratelimit-user-b", "限流考生B");
        String path = "/attempts/999998/answers/999998";

        errorCode(HttpMethod.PUT, path, userA, Map.of("answer", java.util.List.of("A")));
        errorCode(HttpMethod.PUT, path, userA, Map.of("answer", java.util.List.of("A")));
        assertThat(errorCode(HttpMethod.PUT, path, userA, Map.of("answer", java.util.List.of("A"))))
                .isEqualTo("RATE_LIMITED");

        assertThat(errorCode(HttpMethod.PUT, path, userB, Map.of("answer", java.util.List.of("A"))))
                .as("B 的配额不应受 A 影响")
                .isNotEqualTo("RATE_LIMITED");
    }
}
