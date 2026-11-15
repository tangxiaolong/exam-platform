package com.exam.platform.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式令牌桶：脚本返回值映射、key 前缀、失败降级。
 *
 * <p>本机无 Redis 实例，因此用打桩覆盖「脚本返回什么 → 我们怎么判定」这段逻辑。
 * Lua 脚本本身的原子性只能在真实 Redis 上验证，这里额外断言脚本资源可加载，
 * 防止路径写错导致运行时才炸。</p>
 */
@DisplayName("Redis 令牌桶：返回值映射 / 前缀隔离 / 失败降级")
class RedisRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        limiter = new RedisRateLimiter(redisTemplate, meterRegistry, "exam:ratelimit:");
    }

    private RateLimitProperties.Bucket bucket(int capacity, long refillPeriodMs) {
        RateLimitProperties.Bucket config = new RateLimitProperties.Bucket();
        config.setCapacity(capacity);
        config.setRefillTokens(capacity);
        config.setRefillPeriodMs(refillPeriodMs);
        return config;
    }

    @SuppressWarnings("unchecked")
    private void stubScriptResult(List<?> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn((List) result);
    }

    @Test
    @DisplayName("脚本返回 allowed=1 时放行，并透出剩余令牌数")
    void shouldAllowWhenScriptAllows() {
        stubScriptResult(List.of(1L, 7L, 0L));

        RateLimiter.Decision decision = limiter.acquire("answer-save:user:1", bucket(10, 60_000));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(7);
        assertThat(decision.retryAfterMs()).isZero();
    }

    @Test
    @DisplayName("脚本返回 allowed=0 时拒绝，并透出重试等待")
    void shouldDenyWhenScriptDenies() {
        stubScriptResult(List.of(0L, 0L, 3_500L));

        RateLimiter.Decision decision = limiter.acquire("answer-save:user:1", bucket(10, 60_000));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isEqualTo(3_500L);
    }

    @Test
    @DisplayName("拒绝但脚本未给出等待时间时，退化为一个补充周期")
    void shouldFallBackToPeriodWhenRetryAfterMissing() {
        stubScriptResult(List.of(0L, 0L, 0L));

        RateLimiter.Decision decision = limiter.acquire("answer-save:user:1", bucket(10, 60_000));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("key 带上配置前缀，多环境共用 Redis 实例时互不干扰")
    void shouldPrefixKey() {
        stubScriptResult(List.of(1L, 1L, 0L));

        limiter.acquire("answer-save:user:42", bucket(10, 60_000));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("exam:ratelimit:answer-save:user:42")),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 不可用时降级到本地桶：配额按单实例继续生效，而不是全部放行")
    void shouldDegradeToLocalBucketOnBackendError() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // capacity=3：降级后仍应前 3 次放行、第 4 次拒绝。
        // 纯 fail-open 的实现会让 4 次全部放行——Redis 一挂就等于没有限流
        RateLimitProperties.Bucket config = bucket(3, 60_000);
        boolean[] allowed = new boolean[4];
        for (int i = 0; i < 4; i++) {
            allowed[i] = limiter.acquire("answer-save:user:1", config).allowed();
        }
        assertThat(allowed[0]).isTrue();
        assertThat(allowed[1]).isTrue();
        assertThat(allowed[2]).isTrue();
        assertThat(allowed[3]).as("降级后第 4 次应被本地桶拒绝").isFalse();

        assertThat(meterRegistry.counter("app.ratelimit.backend.error").count())
                .as("「真去连 Redis 但失败」只应计一次：后续请求已进入熔断冷却，不该再付连接超时")
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("app.ratelimit.backend.degraded", "decision", "deny").count())
                .as("降级拒绝必须可观测，否则分不清是限流生效还是故障放行")
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("app.ratelimit.backend.degraded", "decision", "allow").count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("熔断冷却期内不再尝试 Redis")
    void shouldBypassRedisDuringCooldown() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        RateLimitProperties.Bucket config = bucket(10, 60_000);
        limiter.acquire("answer-save:user:1", config);
        limiter.acquire("answer-save:user:1", config);
        limiter.acquire("answer-save:user:1", config);

        verify(redisTemplate, times(1))
                .execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("冷却到期后重新试探 Redis，成功即恢复分布式口径")
    void shouldRecoverAfterCooldown() throws InterruptedException {
        // 冷却设短一些，避免用例等待 5 秒
        RedisRateLimiter fast = new RedisRateLimiter(redisTemplate, meterRegistry,
                "exam:ratelimit:", new InMemoryRateLimiter(), 50L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        fast.acquire("answer-save:user:1", bucket(10, 60_000));

        Thread.sleep(80);
        stubScriptResult(List.of(1L, 5L, 0L));

        RateLimiter.Decision recovered = fast.acquire("answer-save:user:1", bucket(10, 60_000));
        assertThat(recovered.allowed()).isTrue();
        assertThat(recovered.remaining())
                .as("remaining=5 来自 Redis，说明已恢复分布式口径而非继续用本地桶")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("脚本返回结构异常时按后端故障处理，同样走降级")
    void shouldDegradeOnUnexpectedResult() {
        stubScriptResult(List.of());

        RateLimiter.Decision decision = limiter.acquire("answer-save:user:1", bucket(10, 60_000));
        assertThat(decision.allowed()).as("结果不可信时不宜直接拒绝正常用户").isTrue();
        assertThat(meterRegistry.counter("app.ratelimit.backend.error").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("app.ratelimit.backend.degraded", "decision", "allow").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("关闭兜底桶时退回纯 fail-open：放行并只记错误指标")
    void shouldFailOpenWhenFallbackDisabled() {
        RedisRateLimiter noFallback = new RedisRateLimiter(redisTemplate, meterRegistry,
                "exam:ratelimit:", null, 5_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(noFallback.acquire("answer-save:user:1", bucket(3, 60_000)).allowed()).isTrue();
        assertThat(meterRegistry.counter("app.ratelimit.backend.error").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("app.ratelimit.backend.degraded").counters())
                .as("未配置兜底桶时不应产出降级判定计数")
                .isEmpty();
    }

    @Test
    @DisplayName("Lua 脚本资源可加载，且使用 Redis 服务端时间而非本机时钟")
    void shouldShipLuaScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("lua/token_bucket.lua");
        assertThat(resource.exists()).as("脚本必须打进 jar").isTrue();
        String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(script).contains("redis.call('TIME')");
        assertThat(script).contains("PEXPIRE");
        assertThat(script).contains("HMGET");
    }
}
