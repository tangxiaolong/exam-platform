package com.exam.platform.common.ratelimit;

/**
 * 令牌桶限流后端。
 *
 * <p>抽象这一层的价值在于：单机部署用进程内桶（零依赖、零网络开销），
 * 多实例部署切到 Redis 桶（配额不按实例数放大）。拦截器只依赖本接口，
 * 换后端不需要改调用方。</p>
 *
 * <p>实现方在后端不可用时应当 <b>fail-open</b>（放行并记指标）：
 * 限流是保护机制而非硬依赖，Redis 抖动不应该让全站接口不可用。</p>
 */
public interface RateLimiter {

    /**
     * 尝试消费一个令牌。
     *
     * @param key    桶的唯一标识，需包含规则名与维度，避免不同规则共用配额
     * @param config 桶参数
     */
    Decision acquire(String key, RateLimitProperties.Bucket config);

    /** 取令牌结果。 */
    record Decision(boolean allowed, int remaining, long retryAfterMs) {

        public static Decision allow(int remaining) {
            return new Decision(true, remaining, 0L);
        }

        public static Decision deny(long retryAfterMs) {
            return new Decision(false, 0, Math.max(1L, retryAfterMs));
        }
    }
}
