package com.exam.platform.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 上的令牌桶边界验证。
 *
 * <p>打桩测试（{@link RedisRateLimiterTest}）只能验证「脚本返回什么 → 我们怎么判定」，
 * 而真正决定配额是否准确的这几件事只有在真 Redis 上才成立：</p>
 * <ul>
 *   <li><b>原子性</b>：并发取令牌不超发——靠的是 Redis 单线程执行 Lua，桩替不了；</li>
 *   <li><b>补充节奏</b>：令牌按服务端时间推进补回，桩只能返回一个假数字；</li>
 *   <li><b>TTL</b>：脚本里的 PEXPIRE 是否真的落地，否则空闲桶会在 Redis 里越积越多；</li>
 *   <li><b>多实例共享</b>：两个限流器实例读同一个 key，总配额不按实例数放大。</li>
 * </ul>
 *
 * <p><b>没有 Redis 时不红构建</b>：连接失败（含需要密码但未配置）直接 abort 成 skipped，
 * 并打印如何开启。默认单机开发与常规 CI 都不需要中间件，但一旦配了 Redis 就会被真正验证。
 * 开启方式：</p>
 * <pre>
 * APP_TEST_REDIS_HOST=127.0.0.1 APP_TEST_REDIS_PORT=6379 \
 * APP_TEST_REDIS_PASSWORD=xxxx APP_TEST_REDIS_DB=15 \
 * mvn test -Dtest=RedisRateLimiterIntegrationTest
 * </pre>
 */
@DisplayName("Redis 令牌桶：真实 Redis 原子性与边界")
class RedisRateLimiterIntegrationTest {

    private static final String HOST = setting("APP_TEST_REDIS_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(setting("APP_TEST_REDIS_PORT", "6379"));
    private static final int DB = Integer.parseInt(setting("APP_TEST_REDIS_DB", "15"));
    private static final String PASSWORD = setting("APP_TEST_REDIS_PASSWORD", null);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void connectOrSkip() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(HOST, PORT);
        configuration.setDatabase(DB);
        if (PASSWORD != null) {
            configuration.setPassword(PASSWORD);
        }
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.ping();
        } catch (Exception e) {
            destroy();
            Assumptions.abort("本机/CI 未提供可用的 Redis，跳过真实原子性验证（" + HOST + ":" + PORT
                    + "，" + e.getMessage() + "）；配置 APP_TEST_REDIS_PASSWORD 等环境变量后会自动执行");
        }
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        destroy();
    }

    @Test
    @DisplayName("初始桶为满额，耗尽后拒绝并给出不超过一个周期的重试等待")
    void shouldStartFullThenDeny() {
        RateLimitProperties.Bucket bucket = bucket(3, 3, 60_000);
        RedisRateLimiter limiter = limiter("it:full:");

        RateLimiter.Decision first = limiter.acquire("user:1", bucket);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).as("满额 3 取走 1 个应剩 2").isEqualTo(2);

        assertThat(limiter.acquire("user:1", bucket).allowed()).isTrue();
        assertThat(limiter.acquire("user:1", bucket).allowed()).isTrue();

        RateLimiter.Decision denied = limiter.acquire("user:1", bucket);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs())
                .as("Retry-After 必须落在 (0, 补充周期] 内，否则前端会等错时长")
                .isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("并发取令牌不超发：容量 10，40 次并发恰好放行 10 次")
    void concurrentAcquireShouldNotOverIssue() throws Exception {
        int capacity = 10;
        RateLimitProperties.Bucket bucket = bucket(capacity, capacity, 60_000);
        RedisRateLimiter limiter = limiter("it:concurrent:");

        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            if (limiter.acquire("shared", bucket).allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("Lua 在 Redis 内原子执行，放行数必须严格等于容量；多于容量说明配额被并发击穿")
                .isEqualTo(capacity);
    }

    @Test
    @DisplayName("跨过一个补充周期后令牌补回，可继续放行")
    void shouldRefillAfterPeriod() throws Exception {
        RateLimitProperties.Bucket bucket = bucket(2, 2, 200);
        RedisRateLimiter limiter = limiter("it:refill:");

        assertThat(limiter.acquire("user:1", bucket).allowed()).isTrue();
        assertThat(limiter.acquire("user:1", bucket).allowed()).isTrue();
        assertThat(limiter.acquire("user:1", bucket).allowed()).isFalse();

        Thread.sleep(350);
        assertThat(limiter.acquire("user:1", bucket).allowed())
                .as("补回一整个周期的令牌后应重新放行")
                .isTrue();
    }

    @Test
    @DisplayName("桶 key 带 TTL：空闲桶会被 Redis 自动回收，不会无限堆积")
    void bucketKeyShouldCarryTtl() {
        RateLimitProperties.Bucket bucket = bucket(5, 5, 60_000);
        RedisRateLimiter limiter = limiter("it:ttl:");
        String key = "it:ttl:user:1";

        limiter.acquire("user:1", bucket);

        Long ttlMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMs)
                .as("脚本必须设置 PEXPIRE，否则每个访问过的 key 都会永久留在 Redis 里")
                .isNotNull()
                .isPositive();
        assertThat(ttlMs)
                .as("TTL 保留两个周期：太短会在补充边界前误删桶，重新计数等于放大配额")
                .isLessThanOrEqualTo(2 * bucket.getRefillPeriodMs());
    }

    @Test
    @DisplayName("两个限流器实例共用同一个 Redis key，总配额不按实例数放大")
    void twoInstancesShouldShareQuota() {
        int capacity = 10;
        RateLimitProperties.Bucket bucket = bucket(capacity, capacity, 60_000);
        // 限流器本身不持有状态，两个实例读同一个 key 等价于两个应用节点
        RedisRateLimiter nodeA = limiter("it:cluster:");
        RedisRateLimiter nodeB = limiter("it:cluster:");

        int allowedByA = 0;
        for (int i = 0; i < 6; i++) {
            if (nodeA.acquire("user:1", bucket).allowed()) {
                allowedByA++;
            }
        }
        int allowedByB = 0;
        for (int i = 0; i < 6; i++) {
            if (nodeB.acquire("user:1", bucket).allowed()) {
                allowedByB++;
            }
        }

        assertThat(allowedByA).isEqualTo(6);
        assertThat(allowedByA + allowedByB)
                .as("多实例共享配额是选用 Redis 后端的唯一理由；若等于 20 说明配额被放大")
                .isEqualTo(capacity);
    }

    // ---------------------------------------------------------------- 辅助

    private RedisRateLimiter limiter(String prefix) {
        return new RedisRateLimiter(redis, meterRegistry, prefix + UUID.randomUUID() + ":");
    }

    private RateLimitProperties.Bucket bucket(int capacity, int refillTokens, long refillPeriodMs) {
        RateLimitProperties.Bucket bucket = new RateLimitProperties.Bucket();
        bucket.setCapacity(capacity);
        bucket.setRefillTokens(refillTokens);
        bucket.setRefillPeriodMs(refillPeriodMs);
        return bucket;
    }

    private void destroy() {
        redis = null;
        if (factory != null) {
            factory.destroy();
            factory = null;
        }
    }

    /** 优先取系统属性（IDE 里更好配），其次环境变量。 */
    private static String setting(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
