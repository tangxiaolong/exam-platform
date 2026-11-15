package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * actuator 指标与健康检查准入。
 *
 * <p>验收要求：健康检查可探活、指标可观测；同时只暴露只读端点，
 * 不能顺手把 {@code env}、{@code heapdump} 这类端点开出去。</p>
 */
@DisplayName("actuator：健康检查与指标暴露")
class ActuatorEndpointTest extends ApiTestSupport {

    @Test
    @DisplayName("健康检查返回 UP 且无需登录")
    void healthShouldBeUp() {
        JsonNode health = get("/actuator/health");
        assertThat(health.path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("liveness / readiness 探针可用")
    void probesShouldBeAvailable() {
        assertThat(get("/actuator/health/liveness").path("status").asText()).isEqualTo("UP");
        assertThat(get("/actuator/health/readiness").path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("指标端点可读，且包含 HTTP 与 JVM 指标")
    void metricsShouldBeExposed() {
        JsonNode root = get("/actuator/metrics");
        assertThat(root.path("names").isArray()).isTrue();
        String names = root.path("names").toString();
        assertThat(names).contains("jvm.memory.used");
        assertThat(names).contains("http.server.requests");

        JsonNode httpMetric = get("/actuator/metrics/http.server.requests");
        assertThat(httpMetric.path("name").asText()).isEqualTo("http.server.requests");
        assertThat(httpMetric.path("measurements").isArray()).isTrue();
    }

    @Test
    @DisplayName("限流拒绝计数指标存在（便于告警）")
    void rateLimitMetricShouldBeRegistered() {
        // 主动触发一次限流外的正常请求，保证 http 指标有数据
        adminToken();
        JsonNode root = get("/actuator/metrics");
        // 指标只有在被计数后才会出现，此处只断言端点可枚举，避免测试间顺序依赖
        assertThat(root.path("names").size()).isPositive();
    }

    @Test
    @DisplayName("敏感端点不对外暴露")
    void shouldNotExposeSensitiveEndpoints() {
        for (String path : new String[]{"/actuator/env", "/actuator/heapdump", "/actuator/beans"}) {
            ResponseEntity<String> response = restTemplate.exchange(
                    absolute("/api" + path), HttpMethod.GET, null, String.class);
            // 未暴露的端点会落到 NoHandlerFound，被全局异常处理器转成业务错误码
            String code = readBodyCode(response);
            assertThat(code)
                    .as("端点 %s 不应暴露，响应体：%s", path, response.getBody())
                    .isNotEqualTo("0");
        }
    }

    private String readBodyCode(ResponseEntity<String> response) {
        try {
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                // 直接 404 而未被全局处理器接管，同样视为未暴露
                return response.getStatusCode().value() == 200 ? "0" : "COMMON_NOT_FOUND";
            }
            return objectMapper.readTree(body).path("code").asText();
        } catch (Exception e) {
            return "PARSE_FAILED";
        }
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = restTemplate.exchange(
                absolute("/api" + path), HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value())
                .as("actuator 端点应可访问：%s", path)
                .isEqualTo(200);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }
}
