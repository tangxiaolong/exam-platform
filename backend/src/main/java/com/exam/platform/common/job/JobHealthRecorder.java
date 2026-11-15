package com.exam.platform.common.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时任务健康上报。
 *
 * <p>解决的问题是「任务没跑 = 没人知道」。此前四个 cron 的失败处理都只有一句
 * {@code log.warn}：日志滚过去就没了，值班时既看不出「上次成功是什么时候」，
 * 也分不清「任务在跑但一直失败」和「调度器压根停摆」。自动交卷停摆意味着考生超时后
 * 卷子永远不交、成绩永远不出；对账停摆意味着资金差异没人发现——这类故障的共同点是
 * <b>静默</b>，必须由指标主动暴露。</p>
 *
 * <p>暴露三组指标（都带 {@code job} 标签）：</p>
 * <ul>
 *   <li>{@code app.job.staleness.seconds}：距上次<b>成功</b>执行多久。从未成功时以进程启动时刻为基准，
 *       这样「部署后一直没跑起来」也会随时间增长而被发现；</li>
 *   <li>{@code app.job.stale.ratio}：staleness / 该任务的预期周期。告警只看这一个比例就够了，
 *       不同任务周期不同（对账 24h、消息重试 60s、自动交卷 30s）也能用同一条规则；</li>
 *   <li>{@code app.job.consecutive.failures}：连续失败次数，成功一次即清零。用来区分
 *       「偶发抖动」与「持续失败」——后者才是需要人介入的。</li>
 * </ul>
 *
 * <p>上报绝不能反过来影响业务：未注册的任务自动按 1 小时周期登记并告警日志，
 * 而不是抛出异常让任务本身崩掉。</p>
 */
@Slf4j
@Component
public class JobHealthRecorder {

    private static final long FALLBACK_INTERVAL_SECONDS = 3_600L;

    private final MeterRegistry meterRegistry;
    private final long startedAtMs = System.currentTimeMillis();
    private final Map<String, JobState> states = new ConcurrentHashMap<>();

    public JobHealthRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 登记任务的预期周期。
     *
     * @param job                   任务名，作为指标标签
     * @param expectedIntervalSeconds 预期执行间隔；与 cron / fixedDelay 配置保持一致
     */
    public void register(String job, long expectedIntervalSeconds) {
        if (expectedIntervalSeconds <= 0) {
            throw new IllegalArgumentException("expectedIntervalSeconds 必须大于 0：" + job);
        }
        states.computeIfAbsent(job, name -> {
            JobState state = new JobState(name, expectedIntervalSeconds);
            Gauge.builder("app.job.staleness.seconds", state, s -> s.stalenessSeconds(startedAtMs))
                    .tag("job", name)
                    .description("距上次成功执行经过的秒数，从未成功则以进程启动时刻为基准")
                    .register(meterRegistry);
            Gauge.builder("app.job.stale.ratio", state, s -> s.staleRatio(startedAtMs))
                    .tag("job", name)
                    .description("staleness / 预期周期，大于 2 即认为该任务未按期执行")
                    .register(meterRegistry);
            Gauge.builder("app.job.consecutive.failures", state, s -> s.failures.get())
                    .tag("job", name)
                    .description("连续失败次数，成功一次清零")
                    .register(meterRegistry);
            Gauge.builder("app.job.last.duration.seconds", state, s -> s.lastDurationMs / 1000.0)
                    .tag("job", name)
                    .description("最近一次执行耗时，成功失败都记")
                    .register(meterRegistry);
            return state;
        });
    }

    public void success(String job, long durationMs) {
        JobState state = require(job);
        state.lastSuccessAtMs = System.currentTimeMillis();
        state.lastDurationMs = durationMs;
        state.failures.set(0);
        state.lastError = null;
    }

    public void failure(String job, long durationMs, Throwable error) {
        JobState state = require(job);
        state.lastDurationMs = durationMs;
        state.failures.incrementAndGet();
        state.lastError = error == null ? null : error.toString();
    }

    /** 当前连续失败次数，便于测试与排障。 */
    public int consecutiveFailures(String job) {
        JobState state = states.get(job);
        return state == null ? 0 : state.failures.get();
    }

    private JobState require(String job) {
        JobState state = states.get(job);
        if (state == null) {
            log.warn("任务 {} 未登记预期周期，按 {}s 兜底上报；请在任务类里调用 register()", job,
                    FALLBACK_INTERVAL_SECONDS);
            register(job, FALLBACK_INTERVAL_SECONDS);
            state = states.get(job);
        }
        return state;
    }

    private static final class JobState {

        private final String name;
        private final long expectedIntervalSeconds;
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long lastSuccessAtMs;
        private volatile long lastDurationMs;
        private volatile String lastError;

        private JobState(String name, long expectedIntervalSeconds) {
            this.name = name;
            this.expectedIntervalSeconds = expectedIntervalSeconds;
        }

        private double stalenessSeconds(long startedAtMs) {
            long base = lastSuccessAtMs > 0 ? lastSuccessAtMs : startedAtMs;
            return Math.max(0L, System.currentTimeMillis() - base) / 1000.0;
        }

        private double staleRatio(long startedAtMs) {
            return stalenessSeconds(startedAtMs) / expectedIntervalSeconds;
        }
    }
}
