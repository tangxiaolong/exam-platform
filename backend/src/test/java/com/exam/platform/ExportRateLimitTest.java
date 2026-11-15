package com.exam.platform;

import com.exam.platform.support.PlainApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出 / 下载类接口的独立配额。
 *
 * <p>默认规则是 300 次/分钟，对「一次请求 = 一整份数据」的导出接口毫无约束力：
 * 一个账号一分钟能把订单全量拉走 300 遍。这里用独立上下文把 export 配额收到 2，
 * 验证它确实生效、且不会被 default 规则吞掉。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 其余规则放宽，只收紧导出：否则登录、查询等前置调用会先把配额吃掉
        "app.rate-limit.default-rule.per-user.capacity=100000",
        "app.rate-limit.default-rule.per-ip.capacity=100000",
        "app.rate-limit.answer-save.per-user.capacity=100000",
        "app.rate-limit.answer-save.per-ip.capacity=100000",
        "app.rate-limit.auth-login.per-user.capacity=100000",
        "app.rate-limit.auth-login.per-ip.capacity=100000",
        "app.rate-limit.export-rule.per-user.capacity=2",
        "app.rate-limit.export-rule.per-user.refill-tokens=1",
        "app.rate-limit.export-rule.per-user.refill-period-ms=60000",
        "app.rate-limit.export-rule.per-ip.capacity=100000"
})
@ActiveProfiles("h2")
@DisplayName("限流：导出 / 下载走独立更严配额")
class ExportRateLimitTest extends PlainApiTestSupport {

    @Test
    @DisplayName("配额内可下载，超限返回 RATE_LIMITED 并带 Retry-After")
    void exportQuotaShouldBeEnforced() throws Exception {
        String admin = adminToken();
        String path = "/admin/reconciliation/anomalies/export";

        ResponseEntity<String> first = exchange(HttpMethod.GET, path, admin, null);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getHeaders().getContentType())
                .as("第一次应正常返回 CSV，而不是被限流")
                .isNotNull();
        assertThat(first.getBody()).doesNotContain("RATE_LIMITED");

        ResponseEntity<String> second = exchange(HttpMethod.GET, path, admin, null);
        assertThat(second.getBody()).doesNotContain("RATE_LIMITED");

        // 第三次触发 RATE_LIMITED：说明 export 规则确实命中该路径，
        // 而不是落到 300 次/分钟的 default（那样永远不会在这个用例里被拒）
        ResponseEntity<String> third = exchange(HttpMethod.GET, path, admin, null);
        assertThat(third.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(third.getBody()).path("code").asText())
                .isEqualTo("RATE_LIMITED");
        assertThat(third.getHeaders().getFirst("Retry-After"))
                .as("限流响应必须给出重试等待，否则调用方只能盲目重试")
                .isNotBlank();
    }
}
