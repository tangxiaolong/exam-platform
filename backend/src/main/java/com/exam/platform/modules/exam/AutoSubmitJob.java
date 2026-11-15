package com.exam.platform.modules.exam;

import com.exam.platform.common.job.JobHealthRecorder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期自动交卷任务。
 *
 * <p>注：任务以平台租户上下文运行（无登录态时 tenantId 默认为平台租户）。
 * 多租户独立部署后，此处需改为按租户遍历。</p>
 *
 * <p>这个任务的停摆后果很具体：考生超时后卷子不交、成绩不出、后续成绩发布与报表全都卡住。
 * 原先异常直接抛给调度器，只在日志里留一行；现在失败会写进 {@code app.job.consecutive.failures}，
 * 连续失败或长时间未成功都会触发告警。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.exam.auto-submit-enabled", havingValue = "true", matchIfMissing = true)
public class AutoSubmitJob {

    static final String JOB_NAME = "auto-submit";
    private static final int BATCH_SIZE = 50;

    private final ExamAttemptService attemptService;
    private final JobHealthRecorder jobHealthRecorder;

    /** 与 {@code app.exam.auto-submit-interval-ms} 同源。 */
    @Value("${app.exam.auto-submit-interval-ms:30000}")
    private long intervalMs;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, Math.max(1L, intervalMs / 1000));
    }

    @Scheduled(fixedDelayString = "${app.exam.auto-submit-interval-ms:30000}")
    public void autoSubmit() {
        long startedAt = System.currentTimeMillis();
        try {
            int count = attemptService.autoSubmitExpired(BATCH_SIZE);
            jobHealthRecorder.success(JOB_NAME, System.currentTimeMillis() - startedAt);
            if (count > 0) {
                log.info("自动交卷完成，共处理 {} 份考卷", count);
            }
        } catch (Exception e) {
            // 不重新抛出：调度器自己再打一遍堆栈只会让日志重复，健康指标已经记下这次失败
            jobHealthRecorder.failure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
            log.error("自动交卷任务异常", e);
        }
    }
}
