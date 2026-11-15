package com.exam.platform.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 令牌桶语义：突发上限、按周期补充、配额耗尽与补充等待时间。 */
@DisplayName("令牌桶：突发上限 / 周期补充 / 耗尽判定")
class TokenBucketTest {

    private RateLimitProperties.Bucket bucket(int capacity, int refillTokens, long refillPeriodMs) {
        RateLimitProperties.Bucket config = new RateLimitProperties.Bucket();
        config.setCapacity(capacity);
        config.setRefillTokens(refillTokens);
        config.setRefillPeriodMs(refillPeriodMs);
        return config;
    }

    @Test
    @DisplayName("容量即突发上限，耗尽后返回 -1")
    void shouldRespectCapacity() {
        RateLimitProperties.Bucket config = bucket(3, 3, 1000);
        TokenBucket tokenBucket = new TokenBucket(3, 0L);

        assertThat(tokenBucket.tryAcquire(config, 0L)).isEqualTo(2);
        assertThat(tokenBucket.tryAcquire(config, 0L)).isEqualTo(1);
        assertThat(tokenBucket.tryAcquire(config, 0L)).isEqualTo(0);
        assertThat(tokenBucket.tryAcquire(config, 0L)).as("配额耗尽").isEqualTo(-1);
    }

    @Test
    @DisplayName("同一周期内不补充，跨周期按 refillTokens 批量补充且不超过容量")
    void shouldRefillPerPeriod() {
        RateLimitProperties.Bucket config = bucket(2, 2, 1000);
        TokenBucket tokenBucket = new TokenBucket(2, 0L);
        tokenBucket.tryAcquire(config, 0L);
        tokenBucket.tryAcquire(config, 0L);
        assertThat(tokenBucket.tryAcquire(config, 500L)).as("未满周期不补充").isEqualTo(-1);

        assertThat(tokenBucket.tryAcquire(config, 1000L)).as("满一个周期补充 2 个").isEqualTo(1);
        assertThat(tokenBucket.tryAcquire(config, 5000L)).as("空闲多个周期也只补到容量上限").isEqualTo(1);
    }

    @Test
    @DisplayName("Retry-After 的等待时间不超过一个补充周期")
    void shouldReportRetryAfter() {
        RateLimitProperties.Bucket config = bucket(1, 1, 1000);
        TokenBucket tokenBucket = new TokenBucket(1, 0L);
        tokenBucket.tryAcquire(config, 0L);

        assertThat(tokenBucket.nextRefillInMs(config, 0L)).isEqualTo(1000);
        assertThat(tokenBucket.nextRefillInMs(config, 900L)).isEqualTo(100);
        assertThat(tokenBucket.nextRefillInMs(config, 1000L)).isEqualTo(1000);
    }

    @Test
    @DisplayName("进程内实现按 key 隔离配额，耗尽时给出重试等待")
    void inMemoryShouldIsolateKeys() {
        RateLimitProperties.Bucket config = bucket(2, 2, 1000);
        RateLimiter limiter = new InMemoryRateLimiter();

        assertThat(limiter.acquire("rule:user:1", config))
                .isEqualTo(new RateLimiter.Decision(true, 1, 0L));
        assertThat(limiter.acquire("rule:user:1", config))
                .isEqualTo(new RateLimiter.Decision(true, 0, 0L));

        RateLimiter.Decision denied = limiter.acquire("rule:user:1", config);
        assertThat(denied.allowed()).as("同 key 已耗尽").isFalse();
        assertThat(denied.retryAfterMs()).isPositive();

        assertThat(limiter.acquire("rule:user:2", config).allowed()).as("不同 key 独立计数").isTrue();
    }

    @Test
    @DisplayName("空闲桶会被清理，避免内存随用户量无限增长")
    void inMemoryShouldEvictIdleBuckets() {
        RateLimitProperties.Bucket config = bucket(2, 2, 60_000);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();

        // 填充到清理阈值以上（阈值 2048）
        for (int i = 0; i < 2100; i++) {
            limiter.acquire("rule:user:" + i, config);
        }
        assertThat(limiter.size()).isGreaterThanOrEqualTo(2100);

        // 空闲 10 个补充周期以上即视为可回收
        limiter.evictIdle(System.currentTimeMillis() + 60_000L * 11);
        assertThat(limiter.size()).isZero();
    }
}
