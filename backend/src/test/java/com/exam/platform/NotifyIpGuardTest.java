package com.exam.platform;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.trade.NotifyIpGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回调 IP 白名单的判定逻辑。
 *
 * <p>不依赖 Spring 上下文：{@code NotifyIpGuard} 只在构造时读一次配置，
 * 放进 {@code @SpringBootTest(properties=...)} 会把整个测试套件多开一个 JVM 上下文，
 * 代价远大于收益。这里直接构造实例 + {@code MockHttpServletRequest} 验四种分支。</p>
 */
@DisplayName("回调 IP 白名单")
class NotifyIpGuardTest {

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    @DisplayName("未配置时一律放行——默认关闭")
    void emptyConfigAllowsEverything() {
        NotifyIpGuard guard = new NotifyIpGuard("");
        // 不抛异常即通过；这条保证本地开发和未接入网关的上线环境不会被挡
        guard.check(request("1.2.3.4"));
    }

    @Test
    @DisplayName("配置后白名单外的 IP 被拒")
    void configuredGuardRejectsUnknownIp() {
        NotifyIpGuard guard = new NotifyIpGuard("10.9.8.7");
        assertThatThrownBy(() -> guard.check(request("1.2.3.4")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在白名单");
    }

    @Test
    @DisplayName("配置后白名单内的 IP 放行")
    void configuredGuardAllowsListedIp() {
        NotifyIpGuard guard = new NotifyIpGuard("10.9.8.7, 10.9.8.8");
        guard.check(request("10.9.8.8"));
    }

    @Test
    @DisplayName("有 X-Forwarded-For 时取最左侧，而不是代理地址")
    void forwardedForTakesLeftmost() {
        NotifyIpGuard guard = new NotifyIpGuard("10.9.8.7");
        MockHttpServletRequest req = request("127.0.0.1");
        req.addHeader("X-Forwarded-For", "10.9.8.7, 172.16.0.1");
        // 若取最右侧会是代理地址 172.16.0.1，白名单匹配失败
        guard.check(req);
    }
}
