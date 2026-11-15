package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 性能基线：答题保存与交卷。
 *
 * <p>这两个接口是集中开考时压力最集中的地方——几百人同时刷新答案、整点同时交卷。
 * 它们慢下来不是「页面转圈」这么简单：答题保存变慢会让考生以为答案丢了、反复重试，
 * 交卷变慢会把收卷队列堆到超时，最后集中触发自动收卷与判分。</p>
 *
 * <p><b>默认不跑</b>：压测结果依赖机器（本地开发机 / CI 共享 runner 的数字没有可比性），
 * 放进常规构建只会让构建变慢且指标无意义。用它来做「改动前后的对比」和「发布前的自查」：</p>
 * <pre>
 * mvn test -Dtest=LoadBaselineTest -Dload.test.enabled=true \
 *          -Dload.test.threads=8 -Dload.test.saves=25
 * </pre>
 *
 * <p>断言只做兜底（p99 不超过 5s），真正的判断标准是历次基线之间的相对变化，
 * 记录在 {@code doc/06_性能基线与压测方法.md}。</p>
 */
@DisplayName("性能基线：答题保存 / 交卷")
@EnabledIfSystemProperty(named = "load.test.enabled", matches = "true")
class LoadBaselineTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int THREADS = Integer.getInteger("load.test.threads", 8);
    private static final int SAVES_PER_THREAD = Integer.getInteger("load.test.saves", 25);

    @Test
    @DisplayName("答题保存：并发写延迟分布与吞吐")
    void answerSaveBaseline() {
        String admin = adminToken();
        Fixture fixture = prepareSession(admin, "压测场次-答题保存", THREADS);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        long start = System.nanoTime();

        runConcurrently(THREADS, (index, ready) -> {
            ready.countDown();
            String token = fixture.tokens().get(index);
            long attemptId = fixture.attemptIds().get(index);
            try {
                ready.await();
                for (int i = 0; i < SAVES_PER_THREAD; i++) {
                    long begin = System.nanoTime();
                    data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/" + fixture.questionId(),
                            token, Map.of("answer", List.of("A")));
                    latencies.add((System.nanoTime() - begin) / 1_000_000);
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(errors.get()).as("压测过程中不应有请求失败").isZero();

        Metrics metrics = Metrics.of(latencies, THREADS, elapsedMs);
        report("答题保存", metrics);
        assertThat(metrics.p99())
                .as("答题保存 p99 兜底阈值；超过说明数据库写链路已明显恶化，先看锁等待与连接池")
                .isLessThan(5000);
    }

    @Test
    @DisplayName("交卷：整点并发交卷延迟分布")
    void submitBaseline() {
        String admin = adminToken();
        int candidates = THREADS;
        Fixture fixture = prepareSession(admin, "压测场次-交卷", candidates);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        long start = System.nanoTime();

        runConcurrently(candidates, (index, ready) -> {
            ready.countDown();
            String token = fixture.tokens().get(index);
            long attemptId = fixture.attemptIds().get(index);
            try {
                ready.await();
                long begin = System.nanoTime();
                data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", token,
                        Map.of("requestId", "load-submit-" + index, "reason", "USER_SUBMIT"));
                latencies.add((System.nanoTime() - begin) / 1_000_000);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(errors.get()).as("压测过程中不应有交卷失败").isZero();

        Metrics metrics = Metrics.of(latencies, candidates, elapsedMs);
        report("交卷（含判分）", metrics);
        assertThat(metrics.p99())
                .as("交卷 p99 兜底阈值；超过说明判分事务过重，需考虑拆解为异步判分")
                .isLessThan(5000);
    }

    // ---------------------------------------------------------------- 压测骨架

    @FunctionalInterface
    private interface Task {
        void run(int index, CountDownLatch ready);
    }

    /**
     * 所有线程先登记、再一起放行：不加这道栅栏的话，第一个线程的请求早已结束，
     * 序列退化成「一个个跑」，测出来的数字和并发毫无关系。
     */
    private void runConcurrently(int threads, Task task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch allSubmitted = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    task.run(index, ready);
                } finally {
                    allSubmitted.countDown();
                }
            });
        }
        try {
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("线程未在 30 秒内全部就绪").isTrue();
            allSubmitted.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private record Metrics(int samples, int concurrency, long elapsedMs,
                           long p50, long p95, long p99, long max) {
        static Metrics of(List<Long> latencies, int concurrency, long elapsedMs) {
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            assertThat(sorted).as("应采集到延迟样本").isNotEmpty();
            return new Metrics(sorted.size(), concurrency, elapsedMs,
                    percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                    sorted.get(sorted.size() - 1));
        }

        private static long percentile(List<Long> sorted, int percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
        }

        double throughput() {
            return elapsedMs <= 0 ? 0 : samples * 1000.0 / elapsedMs;
        }
    }

    private void report(String scene, Metrics metrics) {
        String line = String.format(
                "%-12s 样本=%d 并发=%d 耗时=%dms p50=%dms p95=%dms p99=%dms max=%dms 吞吐=%.1f req/s",
                scene, metrics.samples(), metrics.concurrency(), metrics.elapsedMs(),
                metrics.p50(), metrics.p95(), metrics.p99(), metrics.max(), metrics.throughput());
        System.out.println("[LOAD] " + line);
        try {
            Path path = Path.of("target/load-baseline.md");
            String header = Files.exists(path) ? "" : "| 场景 | 样本 | 并发 | p50 | p95 | p99 | max | 吞吐(req/s) |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n";
            String row = String.format("| %s | %d | %d | %dms | %dms | %dms | %dms | %.1f |%n",
                    scene, metrics.samples(), metrics.concurrency(),
                    metrics.p50(), metrics.p95(), metrics.p99(), metrics.max(), metrics.throughput());
            Files.createDirectories(path.getParent());
            Files.writeString(path, header + row, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 写报告失败不该让压测本身失败，控制台已打印同样的数据
            System.out.println("[LOAD] 写入 target/load-baseline.md 失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 造数

    /** 一个已发布场次 + N 份「已开考」的 attempt，压测线程各用各的，互不争锁。 */
    private record Fixture(long sessionId, long questionId, List<String> tokens, List<Long> attemptIds) {
    }

    private Fixture prepareSession(String admin, String name, int candidates) {
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
        long questionId = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "压测题目",
                "options", List.of(Map.of("key", "A", "text", "选项A"), Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "NORMAL")).path("id").asLong();
        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", name + "-试卷",
                "mode", "FIXED",
                "questions", List.of(Map.<String, Object>of("questionId", questionId, "score", 10)))
        ).path("id").asLong();

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", name);
        body.put("registerStartAt", now.minusHours(1).format(TIME_FORMAT));
        body.put("registerEndAt", now.plusHours(1).format(TIME_FORMAT));
        body.put("startAt", now.format(TIME_FORMAT));
        body.put("endAt", now.plusHours(2).format(TIME_FORMAT));
        body.put("durationMinutes", 120);
        body.put("allowEnterBeforeMinutes", 60);
        body.put("quota", candidates + 50);
        long sessionId = data(HttpMethod.POST, "/admin/sessions", admin, body).path("id").asLong();
        data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);

        List<String> tokens = new ArrayList<>();
        List<Long> attemptIds = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            String token = wechatToken("load-student-" + name + "-" + i, "压测考生" + i);
            data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", token, Map.of());
            JsonNode attempt = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", token, null);
            tokens.add(token);
            attemptIds.add(attempt.path("attemptId").asLong());
        }
        return new Fixture(sessionId, questionId, tokens, attemptIds);
    }
}
