package com.exam.platform.common.config;

import com.exam.platform.common.ratelimit.InMemoryRateLimiter;
import com.exam.platform.common.ratelimit.RateLimitProperties;
import com.exam.platform.common.ratelimit.RateLimiter;
import com.exam.platform.common.ratelimit.RedisRateLimiter;
import com.exam.platform.common.web.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流装配。
 *
 * <p>按 {@code app.rate-limit.backend} 选择实现：</p>
 * <ul>
 *   <li>{@code memory}（默认）：进程内令牌桶，零依赖，适合单体与开发环境；</li>
 *   <li>{@code redis}：分布式令牌桶，多实例部署时配额全局精确。</li>
 * </ul>
 *
 * <p>注意：选 redis 时若容器里没有 {@code StringRedisTemplate}，启动会直接失败而不是静默降级。
 * 限流是准入硬指标，配置与运行环境不一致时应该尽早暴露，而不是悄悄退化成单机配额。</p>
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    @Bean
    public ClientIpResolver clientIpResolver(RateLimitProperties properties) {
        ClientIpResolver resolver = new ClientIpResolver(properties.getTrustedProxies());
        log.info("客户端 IP 解析器已初始化，可信代理网段={}",
                properties.getTrustedProxies().isEmpty() ? "无（不采信转发头）" : properties.getTrustedProxies());
        return resolver;
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate,
                                        MeterRegistry meterRegistry,
                                        RateLimitProperties properties) {
        // Redis 抖动时用本地桶继续判定（口径放宽到单实例），而不是直接放行让配额彻底失效
        RateLimiter fallback = properties.isRedisFallback() ? new InMemoryRateLimiter() : null;
        log.info("限流后端：Redis（分布式），keyPrefix={}，故障策略={}", properties.getKeyPrefix(),
                fallback == null
                        ? "fail-open（放行）"
                        : "降级到本地令牌桶，冷却 " + properties.getRedisDegradedCooldownMs() + "ms");
        return new RedisRateLimiter(redisTemplate, meterRegistry, properties.getKeyPrefix(),
                fallback, properties.getRedisDegradedCooldownMs());
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "memory", matchIfMissing = true)
    public RateLimiter inMemoryRateLimiter() {
        log.info("限流后端：进程内令牌桶（单机）");
        return new InMemoryRateLimiter();
    }
}
