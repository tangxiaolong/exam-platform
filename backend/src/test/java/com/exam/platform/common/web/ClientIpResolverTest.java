package com.exam.platform.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析：转发头只有在直连对端是可信代理时才采信。
 *
 * <p>这组断言守住一个具体的绕过手法：攻击者伪造 {@code X-Forwarded-For}，
 * 每个请求换一个值，如果服务端无条件采信，IP 维度限流就等于不存在。</p>
 */
@DisplayName("客户端 IP 解析：转发头信任边界")
class ClientIpResolverTest {

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("未配置可信代理时，伪造的转发头被忽略")
    void shouldIgnoreForwardedHeaderWhenNoTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());

        // 客户端直连，却伪造了 X-Forwarded-For
        String resolved = resolver.resolve(request("203.0.113.9", "1.2.3.4, 5.6.7.8"));
        assertThat(resolved).as("应使用 socket 对端地址，而非伪造值").isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("直连对端不是可信代理时，转发头同样被忽略")
    void shouldIgnoreForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));

        String resolved = resolver.resolve(request("203.0.113.9", "1.2.3.4"));
        assertThat(resolved).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("经可信代理转发时，取最右侧不可信地址（真实客户端）")
    void shouldPickRightmostUntrustedHop() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.0.0/16"));

        // 客户端在链首伪造了一个值，真实值是代理追加的那一段
        String resolved = resolver.resolve(request("10.0.0.5", "1.1.1.1, 198.51.100.7, 192.168.1.10"));
        assertThat(resolved).as("从右往左找第一个不可信地址").isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("多级代理时跳过可信网段，仍能定位真实客户端")
    void shouldSkipTrustedHops() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));

        String resolved = resolver.resolve(request("10.0.0.5", "198.51.100.7, 10.0.0.9, 10.0.0.11"));
        assertThat(resolved).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("整条链路都可信时退化取最左端")
    void shouldFallBackToLeftmostWhenAllTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));

        String resolved = resolver.resolve(request("10.0.0.5", "10.0.0.1, 10.0.0.2"));
        assertThat(resolved).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("可信代理但无转发头时退回 X-Real-IP，再退回对端地址")
    void shouldFallBackToRealIpThenPeer() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));

        MockHttpServletRequest withRealIp = request("10.0.0.5", null);
        withRealIp.addHeader("X-Real-IP", "198.51.100.7");
        assertThat(resolver.resolve(withRealIp)).isEqualTo("198.51.100.7");

        assertThat(resolver.resolve(request("10.0.0.5", null))).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("单 IP 与 CIDR 两种写法都支持，非法配置被忽略而不影响其余网段")
    void shouldSupportSingleIpAndCidr() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.1.2.3", "not-a-cidr", "192.168.0.0/16"));

        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("192.168.5.5", "198.51.100.8"))).isEqualTo("198.51.100.8");
        assertThat(resolver.resolve(request("10.9.9.9", "198.51.100.9"))).as("不在可信网段").isEqualTo("10.9.9.9");
    }

    @Test
    @DisplayName("X-Forwarded-For 中的 unknown 与空白被跳过")
    void shouldSkipUnknownPlaceholders() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));

        String resolved = resolver.resolve(request("10.0.0.5", "unknown, 198.51.100.7 , unknown"));
        assertThat(resolved).isEqualTo("198.51.100.7");
    }
}
