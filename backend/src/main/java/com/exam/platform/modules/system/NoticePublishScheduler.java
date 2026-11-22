package com.exam.platform.modules.system;

import com.exam.platform.common.job.JobHealthRecorder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公告定时发布任务。
 *
 * <p>运营把生效时间填在未来后，不需要守着表单点「发布」：到点那一刻草稿自动转为已发布。
 * 多实例同时扫是安全的——{@code publishDue} 的 UPDATE 带 {@code status = DRAFT} 条件，
 * 先到者改掉状态，后到者的影响行数为 0。</p>
 *
 * <p>跨租户执行：调度没有请求上下文，SQL 里刻意不带 tenant_id。失败会被记入
 * {@link JobHealthRecorder}，连续失败或长时间未成功会触发告警——与自动交卷任务同一套健康指标。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notice.auto-publish.enabled", havingValue = "true", matchIfMissing = true)
public class NoticePublishScheduler {

    static final String JOB_NAME = "notice-auto-publish";

    private final NoticeService noticeService;
    private final JobHealthRecorder jobHealthRecorder;

    /** 与 {@code notice.auto-publish.interval-ms} 同源。 */
    @Value("${notice.auto-publish.interval-ms:60000}")
    private long intervalMs;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, Math.max(1L, intervalMs / 1000));
    }

    @Scheduled(fixedDelayString = "${notice.auto-publish.interval-ms:60000}")
    public void publishDueNotices() {
        long startedAt = System.currentTimeMillis();
        try {
            int affected = noticeService.publishDue();
            jobHealthRecorder.success(JOB_NAME, System.currentTimeMillis() - startedAt);
            if (affected > 0) {
                log.info("定时发布公告 {} 条", affected);
            }
        } catch (Exception e) {
            // 不重新抛出：调度器再打一遍堆栈只会重复日志，健康指标已经记下这次失败
            jobHealthRecorder.failure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
            log.error("公告定时发布任务异常", e);
        }
    }
}
