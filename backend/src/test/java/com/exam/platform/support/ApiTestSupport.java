package com.exam.platform.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口级集成测试基类：真实启动 Web 容器（随机端口），走完整 Servlet → 拦截器 → 数据库链路，
 * 便于验证鉴权、租户隔离、幂等与状态机等仅靠单元测试覆盖不到的行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 既有用例会高频调用接口，这里放宽配额，避免误触限流；限流本身由 RateLimitFlowTest 专项覆盖
        "app.rate-limit.default-rule.per-user.capacity=100000",
        "app.rate-limit.default-rule.per-ip.capacity=100000",
        "app.rate-limit.answer-save.per-user.capacity=100000",
        "app.rate-limit.answer-save.per-ip.capacity=100000",
        "app.rate-limit.auth-login.per-user.capacity=100000",
        "app.rate-limit.auth-login.per-ip.capacity=100000",
        // 导出/下载的配额默认只有 10/分钟：一条用例里多下载几次就会把后续用例打成 RATE_LIMITED。
        // 配额本身由 ExportRateLimitTest 用独立上下文专门验证，这里放宽避免跨用例互相污染
        "app.rate-limit.export-rule.per-user.capacity=100000",
        "app.rate-limit.export-rule.per-ip.capacity=100000",
        "app.upload.dir=target/test-uploads"
})
@ActiveProfiles("h2")
public abstract class ApiTestSupport extends AbstractApiTestSupport {

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @LocalServerPort
    protected int port;

    /** 带 context-path 的接口地址 */
    protected String api(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    /** 不带 /v1 前缀的绝对地址（支付回调、模拟通知等） */
    protected String absolute(String path) {
        return "http://localhost:" + port + path;
    }

    protected JsonNode call(HttpMethod method, String path, String token, Object body) {
        return callUrl(api(path), method, token, body);
    }

    protected JsonNode callUrl(String url, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String payload = serialize(body);
        ResponseEntity<String> response = restTemplate.exchange(url, method,
                new HttpEntity<>(payload, headers), String.class);
        assertThat(response.getStatusCode().value())
                .as("HTTP 状态应为 200，响应体=%s", response.getBody())
                .isEqualTo(200);
        return parse(response.getBody());
    }

    /** 断言成功并返回 data 节点 */
    protected JsonNode data(HttpMethod method, String path, String token, Object body) {
        JsonNode result = call(method, path, token, body);
        assertThat(result.path("code").asText())
                .as("接口应返回成功，实际：%s", result)
                .isEqualTo("0");
        return result.path("data");
    }

    protected JsonNode data(String path, String token) {
        return data(HttpMethod.GET, path, token, null);
    }

    /** 绝对地址版本（用于支付回调等不在 /v1 前缀下的接口） */
    protected JsonNode dataUrl(String path, HttpMethod method, String token, Object body) {
        JsonNode result = callUrl(absolute(path), method, token, body);
        assertThat(result.path("code").asText())
                .as("接口应返回成功，实际：%s", result)
                .isEqualTo("0");
        return result.path("data");
    }

    /** 断言业务失败并返回错误码 */
    protected String errorCode(HttpMethod method, String path, String token, Object body) {
        JsonNode result = call(method, path, token, body);
        String code = result.path("code").asText();
        assertThat(code).as("应返回业务错误码，实际：%s", result).isNotEqualTo("0");
        return code;
    }

    protected String adminToken() {
        JsonNode data = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD));
        return data.path("token").asText();
    }

    protected String wechatToken(String code, String nickname) {
        JsonNode data = data(HttpMethod.POST, "/auth/wechat-login", null,
                Map.of("code", code, "nickname", nickname));
        return data.path("token").asText();
    }

    protected long firstProjectId(String adminToken) {
        JsonNode page = data("/projects?pageNo=1&pageSize=1", adminToken);
        assertThat(page.path("list").size()).as("演示项目应存在").isGreaterThan(0);
        return page.path("list").get(0).path("id").asLong();
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应不是合法 JSON：" + body, e);
        }
    }
}
