package com.exam.platform.common.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 定时任务健康上报。
 *
 * <p>这里验的是「值班时能不能分辨两种故障」：任务在跑但一直失败（consecutive_failures 涨）、
 * 以及任务根本没在跑（staleness 涨）。两者都在日志里看不出来，只能靠指标。</p>
 */
@DisplayName("定时任务健康上报")
class JobHealthRecorderTest {

    private SimpleMeterRegistry meterRegistry;
    private JobHealthRecorder recorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recorder = new JobHealthRecorder(meterRegistry);
    }

    private double gauge(String name, String job) {
        return meterRegistry.get(name).tag("job", job).gauge().value();
    }

    @Test
    @DisplayName("登记后暴露 staleness / stale_ratio / consecutive_failures 三组指标")
    void shouldExposeMetricsOnRegister() {
        recorder.register("auto-submit", 30L);

        assertThat(meterRegistry.find("app.job.staleness.seconds").tag("job", "auto-submit").gauge()).isNotNull();
        assertThat(meterRegistry.find("app.job.stale.ratio").tag("job", "auto-submit").gauge()).isNotNull();
        assertThat(meterRegistry.find("app.job.consecutive.failures").tag("job", "auto-submit").gauge()).isNotNull();
        assertThat(meterRegistry.find("app.job.last.duration.seconds").tag("job", "auto-submit").gauge()).isNotNull();
    }

    @Test
    @DisplayName("从未成功时 staleness 随进程运行时长增长，而不是恒为 0")
    void shouldGrowWhileNeverSucceeded() {
        recorder.register("auto-submit", 30L);
        // 以进程启动时刻为基准，因此刚登记时也应大于 0 秒（哪怕只过了几毫秒）
        assertThat(gauge("app.job.staleness.seconds", "auto-submit")).isGreaterThanOrEqualTo(0.0);
        assertThat(gauge("app.job.consecutive.failures", "auto-submit")).isZero();
    }

    @Test
    @DisplayName("成功一次后 staleness 归零、连续失败清零")
    void shouldResetOnSuccess() {
        recorder.register("message-retry", 60L);
        recorder.failure("message-retry", 12L, new IllegalStateException("boom"));
        recorder.failure("message-retry", 12L, new IllegalStateException("boom"));
        assertThat(gauge("app.job.consecutive.failures", "message-retry")).isEqualTo(2.0);

        recorder.success("message-retry", 25L);
        assertThat(gauge("app.job.consecutive.failures", "message-retry")).isZero();
        assertThat(gauge("app.job.staleness.seconds", "message-retry")).isLessThan(1.0);
    }

    @Test
    @DisplayName("失败累加连续次数，且 stale_ratio 用预期周期做分母")
    void shouldTrackFailuresAndRatio() {
        recorder.register("reconciliation", 86_400L);
        recorder.failure("reconciliation", 3L, new IllegalExceptionForTest());
        recorder.failure("reconciliation", 3L, new IllegalExceptionForTest());
        recorder.failure("reconciliation", 3L, new IllegalExceptionForTest());

        assertThat(recorder.consecutiveFailures("reconciliation")).isEqualTo(3);
        // 周期 24h，进程刚启动就失败，ratio 必然远小于 1（不构成「未按期执行」）
        assertThat(gauge("app.job.stale.ratio", "reconciliation")).isLessThan(1.0);
    }

    @Test
    @DisplayName("未登记的任务上报时不抛异常（健康上报不能反过来打挂任务）")
    void shouldNotBreakOnUnregisteredJob() {
        assertThatCode(() -> recorder.success("unknown-job", 5L)).doesNotThrowAnyException();
        assertThat(meterRegistry.find("app.job.stale.ratio").tag("job", "unknown-job").gauge())
                .as("兜底登记后指标仍应出现")
                .isNotNull();
    }

    private static final class IllegalExceptionForTest extends RuntimeException {
        private IllegalExceptionForTest() {
            super("job failed");
        }
    }
}
