package com.exam.platform.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶实现（单机 / 开发环境）。
 *
 * <p>零依赖、无网络开销，但配额是「每实例」的：多实例部署时实际总配额 = 配置值 × 实例数。
 * 需要全局精确配额时改用 {@link RedisRateLimiter}。</p>
 *
 * <p>桶按 key 惰性创建，并定期清理长时间未访问的桶，避免用户量增长导致内存泄漏。</p>
 */
@Slf4j
public class InMemoryRateLimiter implements RateLimiter {

    /** 超过该空闲时长（相对补充周期倍数）的桶会被清理。 */
    private static final long IDLE_PERIODS_BEFORE_EVICT = 10L;
    /** 桶数量超过该阈值才触发清理，避免每次请求都全量扫描。 */
    private static final int EVICT_THRESHOLD = 2048;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    /** 记录每个桶的补充周期，供清理时估算空闲时长。 */
    private final Map<String, Long> periods = new ConcurrentHashMap<>();

    @Override
    public Decision acquire(String key, RateLimitProperties.Bucket config) {
        long now = System.currentTimeMillis();
        int capacity = Math.max(1, config.getCapacity());
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> {
            periods.put(k, Math.max(1L, config.getRefillPeriodMs()));
            return new TokenBucket(capacity, now);
        });
        int remaining = bucket.tryAcquire(config, now);
        if (remaining < 0) {
            return Decision.deny(bucket.nextRefillInMs(config, now));
        }
        return Decision.allow(remaining);
    }

    public int size() {
        return buckets.size();
    }

    /** 定期清理空闲桶。 */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void scheduledEvict() {
        evictIdle(System.currentTimeMillis());
    }

    /** 便于测试直接驱动清理逻辑。 */
    public void evictIdle(long now) {
        if (buckets.size() < EVICT_THRESHOLD) {
            return;
        }
        int before = buckets.size();
        for (Map.Entry<String, TokenBucket> entry : buckets.entrySet()) {
            long period = periods.getOrDefault(entry.getKey(), 60_000L);
            long idleLimit = Math.max(1L, period) * IDLE_PERIODS_BEFORE_EVICT;
            if (now - entry.getValue().lastAccessAt() > idleLimit) {
                buckets.remove(entry.getKey(), entry.getValue());
                periods.remove(entry.getKey());
            }
        }
        log.debug("限流桶清理 {} -> {}", before, buckets.size());
    }
}
