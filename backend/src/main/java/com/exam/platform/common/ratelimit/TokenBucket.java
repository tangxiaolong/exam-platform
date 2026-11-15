package com.exam.platform.common.ratelimit;

/**
 * 单个令牌桶状态。
 *
 * <p>采用惰性补充：每次取令牌时按「已过去的完整补充周期数」批量补令牌，
 * 无需后台线程推进，也不会因为长时间空闲而无限累积（上限即 capacity）。</p>
 */
final class TokenBucket {

    private int tokens;
    private long lastRefillAt;
    private volatile long lastAccessAt;

    TokenBucket(int capacity, long now) {
        this.tokens = Math.max(1, capacity);
        this.lastRefillAt = now;
        this.lastAccessAt = now;
    }

    /**
     * 尝试取一个令牌。
     *
     * @return 取到令牌时返回剩余令牌数（>=0）；配额耗尽返回 -1
     */
    synchronized int tryAcquire(RateLimitProperties.Bucket config, long now) {
        lastAccessAt = now;
        refill(config, now);
        if (tokens <= 0) {
            return -1;
        }
        tokens--;
        return tokens;
    }

    /**
     * 距离下一次补充令牌还需等待的毫秒数，用于返回 Retry-After。
     *
     * <p>调用方只在「已耗尽配额」时使用，说明本周期内的令牌已被拿光，
     * 因此等待时间必须落在下一个补充边界上：恰好停在边界时等满一个周期。</p>
     */
    synchronized long nextRefillInMs(RateLimitProperties.Bucket config, long now) {
        long period = Math.max(1L, config.getRefillPeriodMs());
        long elapsed = Math.max(0L, now - lastRefillAt);
        long remainder = elapsed % period;
        long wait = remainder == 0 ? period : period - remainder;
        return Math.max(1L, wait);
    }

    synchronized long lastAccessAt() {
        return lastAccessAt;
    }

    private void refill(RateLimitProperties.Bucket config, long now) {
        long period = Math.max(1L, config.getRefillPeriodMs());
        long elapsed = now - lastRefillAt;
        if (elapsed < period) {
            return;
        }
        long periods = elapsed / period;
        int capacity = Math.max(1, config.getCapacity());
        long replenished = (long) Math.max(1, config.getRefillTokens()) * periods;
        this.tokens = (int) Math.min(capacity, this.tokens + replenished);
        this.lastRefillAt += periods * period;
    }
}
