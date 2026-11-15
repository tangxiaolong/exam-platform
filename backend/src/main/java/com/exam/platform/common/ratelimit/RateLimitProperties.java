package com.exam.platform.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流配置。
 *
 * <p>令牌桶按「规则 × 维度」独立计数：同一规则下按用户与按 IP 各一个桶，
 * 任一维度耗尽即拒绝，避免单账号刷接口与单 IP 批量请求互相掩护。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    public static final String RULE_DEFAULT = "default";
    public static final String RULE_ANSWER_SAVE = "answer-save";
    public static final String RULE_AUTH_LOGIN = "auth-login";
    public static final String RULE_EXPORT = "export";

    public static final String BACKEND_MEMORY = "memory";
    public static final String BACKEND_REDIS = "redis";

    /** 限流后端：memory（进程内，单机） / redis（分布式，多实例）。 */
    private String backend = BACKEND_MEMORY;
    /** Redis 后端的 key 前缀，多环境共用实例时靠它隔离。 */
    private String keyPrefix = "exam:ratelimit:";
    /**
     * 可信反向代理网段。只有直连对端命中这里，才会采信 X-Forwarded-For。
     *
     * <p>默认空表示「不信任任何转发头」——这是安全的默认值：
     * 直接采信转发头会让客户端伪造一个头就绕过 IP 维度限流。</p>
     */
    private List<String> trustedProxies = new ArrayList<>();

    private boolean enabled = true;

    /**
     * Redis 后端抖动时是否降级到进程内令牌桶（默认开）。
     *
     * <p>不开时是纯 fail-open：请求照常放行，但配额完全失效——Redis 一挂，
     * 「防刷」这件事整体消失，只能靠告警事后发现。开了以后配额按单实例口径继续生效，
     * 多实例场景下实际总配额会放大到「配置值 × 实例数」，这是有意的取舍：
     * 口径放宽好过彻底不设防。</p>
     */
    private boolean redisFallback = true;

    /**
     * 降级后的熔断冷却时长（毫秒）。
     *
     * <p>Redis 故障期间如果每个请求都先尝试一次 Redis，就要为每个请求付一次连接超时，
     * 限流反而成了拖慢所有接口的元凶。冷却期内直接走本地桶，到期再试探一次（半开恢复）。</p>
     */
    private long redisDegradedCooldownMs = 5_000L;

    private Rule defaultRule = new Rule();
    private Rule answerSave = new Rule();
    private Rule authLogin = new Rule();
    /**
     * 导出 / 下载类规则。
     *
     * <p>单独配一组更严的配额，原因是这类接口的「一次请求」等于「一整份数据」：
     * 订单报表一次就是全量订单，异常清单一次是全部待处置记录。走 default 的 300/分钟
     * 意味着一个账号一分钟能把整库拉走 300 遍，而它平时一分钟只会点两次。
     * 人手动下载不会超过 10 次/分钟，自动化爬取一定会。</p>
     */
    private Rule exportRule = new Rule();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRedisFallback() {
        return redisFallback;
    }

    public void setRedisFallback(boolean redisFallback) {
        this.redisFallback = redisFallback;
    }

    public long getRedisDegradedCooldownMs() {
        return redisDegradedCooldownMs;
    }

    public void setRedisDegradedCooldownMs(long redisDegradedCooldownMs) {
        this.redisDegradedCooldownMs = redisDegradedCooldownMs;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public Rule getDefaultRule() {
        return defaultRule;
    }

    public void setDefaultRule(Rule defaultRule) {
        this.defaultRule = defaultRule;
    }

    public Rule getAnswerSave() {
        return answerSave;
    }

    public void setAnswerSave(Rule answerSave) {
        this.answerSave = answerSave;
    }

    public Rule getAuthLogin() {
        return authLogin;
    }

    public void setAuthLogin(Rule authLogin) {
        this.authLogin = authLogin;
    }

    public Rule getExportRule() {
        return exportRule;
    }

    public void setExportRule(Rule exportRule) {
        this.exportRule = exportRule;
    }

    /** 单条限流规则。 */
    public static class Rule {

        /** 路径模式，按 Spring MVC 路径通配符匹配。 */
        private List<String> paths = new ArrayList<>();
        private Bucket perUser = new Bucket();
        private Bucket perIp = new Bucket();

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public Bucket getPerUser() {
            return perUser;
        }

        public void setPerUser(Bucket perUser) {
            this.perUser = perUser;
        }

        public Bucket getPerIp() {
            return perIp;
        }

        public void setPerIp(Bucket perIp) {
            this.perIp = perIp;
        }
    }

    /** 令牌桶参数：capacity 为突发上限，每 refillPeriodMs 补充 refillTokens 个令牌。 */
    public static class Bucket {

        private int capacity = 60;
        private int refillTokens = 60;
        private long refillPeriodMs = 60_000L;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(int refillTokens) {
            this.refillTokens = refillTokens;
        }

        public long getRefillPeriodMs() {
            return refillPeriodMs;
        }

        public void setRefillPeriodMs(long refillPeriodMs) {
            this.refillPeriodMs = refillPeriodMs;
        }
    }
}
