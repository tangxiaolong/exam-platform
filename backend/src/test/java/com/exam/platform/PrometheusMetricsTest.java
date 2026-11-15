package com.exam.platform;

import com.exam.platform.support.PlainApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prometheus 抓取端点验证。
 *
 * <p>告警规则的表达式全部建立在指标名与标签上，指标名一旦对不上，
 * 规则会「语法正确但永不触发」——这是最坏的一种失效：看起来有监控，实际没有。
 * 因此这里断言的是抓取端点的真实输出，而不是配置文件里的字面量。</p>
 *
 * <p>使用独立的低配额上下文：限流拒绝计数只有在真的被拒绝后才出现，
 * 高配额上下文里这个指标永远不会生成。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
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
@DisplayName("可观测性：Prometheus 抓取端点")
class PrometheusMetricsTest extends PlainApiTestSupport {

    @Test
    @DisplayName("抓取端点可用，且包含 JVM / HTTP 直方图 / 连接池指标")
    void prometheusEndpointShouldExposeCoreMetrics() {
        // HTTP 指标是请求结束后才计入的，抓取自身那条还没结算，
        // 因此先打一个正常请求，避免用例依赖「抓取顺序」这种巧合
        adminToken();
        String body = scrape();

        assertThat(body).contains("jvm_memory_used_bytes");
        // 直方图是 p95/p99 告警的前提，缺失说明 distribution 配置没生效
        assertThat(body).contains("http_server_requests_seconds_bucket");
        assertThat(body).contains("hikaricp_connections_active");
        assertThat(body).contains("hikaricp_connections_acquire_seconds_bucket");
        assertThat(body).contains("application=\"exam-platform-server\"");
    }

    @Test
    @DisplayName("限流拒绝计数带 rule/dimension 标签导出，告警规则才能按规则拆分")
    void rateLimitRejectionShouldBeScrapable() {
        String student = wechatToken("prom-metrics-user", "指标考生");
        String path = "/attempts/999997/answers/999997";
        // 配额 2：打到第三次才产生拒绝
        errorCode(HttpMethod.PUT, path, student, Map.of("answer", List.of("A")));
        errorCode(HttpMethod.PUT, path, student, Map.of("answer", List.of("A")));
        errorCode(HttpMethod.PUT, path, student, Map.of("answer", List.of("A")));

        String body = scrape();
        assertThat(body).contains("app_ratelimit_rejected_total");
        assertThat(body)
                .as("拒绝计数必须带 rule 标签，否则告警无法定位是哪条规则")
                .contains("rule=\"answer-save\"");
        // 维度标签由拦截器常量导出为大写（USER / IP），告警规则里按此大小写匹配
        assertThat(body).contains("dimension=\"USER\"");
    }

    private String scrape() {
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/actuator/prometheus",
                HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value())
                .as("Prometheus 端点应可直接抓取，响应体：%s", response.getBody())
                .isEqualTo(200);
        return response.getBody() == null ? "" : response.getBody();
    }
}
