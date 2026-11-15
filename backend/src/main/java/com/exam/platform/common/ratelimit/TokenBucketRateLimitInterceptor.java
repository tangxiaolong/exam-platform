package com.exam.platform.common.ratelimit;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.web.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 限流拦截器：令牌桶 + 按用户 / IP 双维度配额。
 *
 * <p>顺序上必须排在 {@code AuthInterceptor} 之后，否则拿不到登录主体，
 * 用户维度会退化成 IP 维度。</p>
 *
 * <p>双维度是「且」的关系：任一维度配额耗尽即拒绝。仅按用户限流挡不住批量注册刷号，
 * 仅按 IP 限流挡不住同一出口下的多账号刷接口。</p>
 *
 * <p>具体存储由 {@link RateLimiter} 决定：单机用进程内桶，多实例用 Redis 桶。
 * 拦截器本身不感知后端差异。</p>
 */
@Slf4j
@Component
public class TokenBucketRateLimitInterceptor implements HandlerInterceptor {

    /** 未显式配置时的兜底路径：答题保存。 */
    private static final List<String> DEFAULT_ANSWER_SAVE_PATHS =
            List.of("/v1/attempts/*/answers/*");
    /** 未显式配置时的兜底路径：登录（含微信登录），按 IP 抑制撞库。 */
    private static final List<String> DEFAULT_AUTH_LOGIN_PATHS =
            List.of("/v1/auth/login", "/v1/auth/wechat-login");
    /** 未显式配置时的兜底路径：导出 / 下载（一次请求 = 一整份数据）。 */
    private static final List<String> DEFAULT_EXPORT_PATHS =
            List.of("/v1/admin/exports/*/download", "/v1/admin/reconciliation/anomalies/export");

    private static final String METRIC_REJECTED = "app.ratelimit.rejected";

    private final RateLimitProperties properties;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public TokenBucketRateLimitInterceptor(RateLimitProperties properties,
                                           RateLimiter rateLimiter,
                                           ClientIpResolver clientIpResolver,
                                           MeterRegistry meterRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = relativePath(request);
        ResolvedRule resolved = resolve(path);
        RateLimitProperties.Rule rule = resolved.rule();

        LoginUser user = UserContext.get();
        Long userId = user == null ? null : user.getUserId();

        // 先消费用户维度，再消费 IP 维度：两者都通过才放行
        if (userId != null) {
            String userKey = resolved.name() + ":user:" + userId;
            RateLimiter.Decision decision = rateLimiter.acquire(userKey, rule.getPerUser());
            if (!decision.allowed()) {
                reject(response, resolved.name(), "USER", userKey, decision.retryAfterMs());
            }
        }
        String ipKey = resolved.name() + ":ip:" + clientIpResolver.resolve(request);
        RateLimiter.Decision ipDecision = rateLimiter.acquire(ipKey, rule.getPerIp());
        if (!ipDecision.allowed()) {
            reject(response, resolved.name(), "IP", ipKey, ipDecision.retryAfterMs());
        }
        return true;
    }

    private void reject(HttpServletResponse response, String rule, String dimension,
                        String key, long retryAfterMs) {
        long retryAfterSeconds = Math.max(1L, (Math.max(1L, retryAfterMs) + 999L) / 1000L);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        meterRegistry.counter(METRIC_REJECTED, "rule", rule, "dimension", dimension).increment();
        // key 里可能含 IP，走日志脱敏兜底；这里只打规则与维度，足够定位
        log.warn("触发限流 rule={} dimension={} key={} retryAfter={}s", rule, dimension, key, retryAfterSeconds);
        throw new BizException("RATE_LIMITED",
                "操作过于频繁，请 " + retryAfterSeconds + " 秒后再试");
    }

    /** 路径去掉 context-path，便于与配置中的模式对齐。 */
    private String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
    }

    private ResolvedRule resolve(String path) {
        // 导出类规则优先：它的路径最具体，且配额最严，不能被 default 兜底吞掉
        RateLimitProperties.Rule exportRule = properties.getExportRule();
        if (matchesAny(path, exportRule.getPaths(), DEFAULT_EXPORT_PATHS)) {
            return new ResolvedRule(RateLimitProperties.RULE_EXPORT, exportRule);
        }
        RateLimitProperties.Rule answerSave = properties.getAnswerSave();
        if (matchesAny(path, answerSave.getPaths(), DEFAULT_ANSWER_SAVE_PATHS)) {
            return new ResolvedRule(RateLimitProperties.RULE_ANSWER_SAVE, answerSave);
        }
        RateLimitProperties.Rule authLogin = properties.getAuthLogin();
        if (matchesAny(path, authLogin.getPaths(), DEFAULT_AUTH_LOGIN_PATHS)) {
            return new ResolvedRule(RateLimitProperties.RULE_AUTH_LOGIN, authLogin);
        }
        return new ResolvedRule(RateLimitProperties.RULE_DEFAULT, properties.getDefaultRule());
    }

    private boolean matchesAny(String path, List<String> configured, List<String> fallback) {
        List<String> patterns = configured == null || configured.isEmpty() ? fallback : configured;
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private record ResolvedRule(String name, RateLimitProperties.Rule rule) {
    }
}
