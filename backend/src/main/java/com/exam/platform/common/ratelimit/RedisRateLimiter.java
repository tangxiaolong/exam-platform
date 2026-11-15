package com.exam.platform.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 分布式令牌桶（Redis + Lua）。
 *
 * <p>多实例部署时用它替换 {@link InMemoryRateLimiter}：配额是全局的，
 * 不会因为多开实例而线性放大。整个取令牌过程在 Redis 单线程内原子完成，
 * 不需要分布式锁。</p>
 *
 * <p><b>失败策略：降级而非放行。</b>早期实现是纯 fail-open（Redis 抖动时一律放行），
 * 理由是「限流是保护措施、不是业务硬依赖」。但那个取舍漏掉了一层：fail-open 意味着
 * Redis 一挂，防刷这件事整体消失，只剩一条告警提醒有人可以无限刷——攻击者恰好会在
 * 你忙着救 Redis 的时候刷进来。所以现在改为 <b>fail-degraded</b>：</p>
 *
 * <ol>
 *   <li>Redis 取令牌抛错 → 立即切到进程内令牌桶（同 key、同配置），配额按<b>单实例</b>口径继续生效；
 *       多实例时实际总配额会放大成「配置值 × 实例数」，口径放宽好过彻底不设防。</li>
 *   <li>同时置一段熔断冷却：冷却期内不再尝试 Redis，避免每个请求都先付一次连接超时
 *       ——否则限流本身会变成拖慢全站接口的元凶。冷却到期自动试探一次，成功即恢复分布式口径。</li>
 *   <li>两条路径都用指标区分：{@code app.ratelimit.backend.error} 是「真去连 Redis 但失败」，
 *       {@code app.ratelimit.backend.degraded} 是「这次是本地桶判的」，便于判断降级持续多久、
 *       有没有把正常用户误拒。</li>
 * </ol>
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final String DEFAULT_PREFIX = "exam:ratelimit:";
    private static final String METRIC_BACKEND_ERROR = "app.ratelimit.backend.error";
    private static final String METRIC_DEGRADED = "app.ratelimit.backend.degraded";
    private static final long DEFAULT_COOLDOWN_MS = 5_000L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> script;
    private final MeterRegistry meterRegistry;
    private final String keyPrefix;
    /** Redis 不可用时接管判定的本地桶；为 null 表示不降级（退回纯 fail-open）。 */
    private final RateLimiter fallback;
    private final long degradedCooldownMs;

    /** 熔断截止时间：在此之前不再尝试 Redis，直接走本地桶。0 表示未降级。 */
    private volatile long degradedUntilMs;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry, String keyPrefix) {
        this(redisTemplate, meterRegistry, keyPrefix, new InMemoryRateLimiter(), DEFAULT_COOLDOWN_MS);
    }

    public RedisRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry, String keyPrefix,
                            RateLimiter fallback, long degradedCooldownMs) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? DEFAULT_PREFIX : keyPrefix;
        this.fallback = fallback;
        this.degradedCooldownMs = Math.max(0L, degradedCooldownMs);
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    @Override
    public Decision acquire(String key, RateLimitProperties.Bucket config) {
        if (System.currentTimeMillis() < degradedUntilMs) {
            // 熔断期内直接兜底：不要为了「一定会失败的 Redis 调用」再付一次超时
            return judgeLocally(key, config, false);
        }
        try {
            List<?> result = redisTemplate.execute(script, List.of(keyPrefix + key),
                    String.valueOf(Math.max(1, config.getCapacity())),
                    String.valueOf(Math.max(1, config.getRefillTokens())),
                    String.valueOf(Math.max(1L, config.getRefillPeriodMs())));
            Decision decision = toDecision(result, config);
            if (decision == null) {
                // 脚本返回异常（null / 字段不足）与连接失败同性质：结果不可信，按后端故障处理
                return onBackendFailure(key, config, null, "脚本返回结果异常 result=" + result);
            }
            // 成功一次即解除熔断：恢复分布式口径不需要人工介入
            degradedUntilMs = 0L;
            return decision;
        } catch (Exception e) {
            return onBackendFailure(key, config, e, e.getMessage());
        }
    }

    /** 真实尝试 Redis 失败：计数、进入熔断冷却，然后用本地桶给出判定。 */
    private Decision onBackendFailure(String key, RateLimitProperties.Bucket config, Exception cause, String reason) {
        meterRegistry.counter(METRIC_BACKEND_ERROR).increment();
        degradedUntilMs = System.currentTimeMillis() + degradedCooldownMs;
        return judgeLocally(key, config, true, cause, reason);
    }

    private Decision judgeLocally(String key, RateLimitProperties.Bucket config, boolean firstFailure) {
        return judgeLocally(key, config, firstFailure, null, null);
    }

    /**
     * 用本地令牌桶给出判定。
     *
     * @param firstFailure true 表示这是本次降级的第一次失败（需要 warn 级别留痕），
     *                     熔断期内的后续请求走 debug，避免故障期间把日志刷满
     */
    private Decision judgeLocally(String key, RateLimitProperties.Bucket config, boolean firstFailure,
                                  Exception cause, String reason) {
        if (fallback == null) {
            // 未配置本地桶：只能放行。此时不算「降级判定」——没有任何桶参与判定，
            // 记 degraded 会让「降级持续多久」这个判断失真
            log.warn("限流后端不可用，未配置兜底桶，本次放行 key={} 原因={}", key, reason, cause);
            return Decision.allow(Integer.MAX_VALUE);
        }
        Decision decision = fallback.acquire(key, config);
        meterRegistry.counter(METRIC_DEGRADED, "decision", decision.allowed() ? "allow" : "deny").increment();
        if (firstFailure) {
            log.warn("限流后端不可用，已降级为本地令牌桶（{}ms 冷却）key={} allowed={} 原因={}",
                    degradedCooldownMs, key, decision.allowed(), reason, cause);
        } else {
            log.debug("限流处于降级期，本次由本地令牌桶判定 key={} allowed={}", key, decision.allowed());
        }
        return decision;
    }

    /** 结果不可信时返回 null，由调用方按后端故障处理。 */
    private Decision toDecision(List<?> result, RateLimitProperties.Bucket config) {
        if (result == null || result.size() < 3) {
            return null;
        }
        long allowed = asLong(result.get(0));
        int remaining = (int) Math.max(0L, asLong(result.get(1)));
        long retryAfterMs = asLong(result.get(2));
        if (allowed == 1L) {
            return Decision.allow(remaining);
        }
        return Decision.deny(retryAfterMs <= 0 ? config.getRefillPeriodMs() : retryAfterMs);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
