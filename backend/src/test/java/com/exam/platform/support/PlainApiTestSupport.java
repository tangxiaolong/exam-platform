package com.exam.platform.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轻量接口测试基类：不绑定 {@code @SpringBootTest}，由子类自行声明注解与属性。
 *
 * <p>限流用例需要「低配额」的独立上下文，而 {@link ApiTestSupport} 为了既有用例
 * 把配额调到了极大值，两者不能共用一个 Spring 上下文。</p>
 */
public abstract class PlainApiTestSupport extends AbstractApiTestSupport {

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    protected int port;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected String api(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    protected JsonNode call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response = restTemplate.exchange(api(path), method,
                new HttpEntity<>(serialize(body), headers), String.class);
        assertThat(response.getStatusCode().value())
                .as("HTTP 状态应为 200，响应体=%s", response.getBody())
                .isEqualTo(200);
        return parse(response.getBody());
    }

    /** 返回完整响应，便于断言响应头（如 Retry-After）。 */
    protected ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(api(path), method, new HttpEntity<>(serialize(body), headers), String.class);
    }

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
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + body, e);
        }
    }
}
