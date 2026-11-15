package com.exam.platform.modules.system.job;

import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.modules.system.service.AuditArchiveService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计日志归档任务。
 *
 * <p>只在 {@code app.audit.archive.enabled=true} 时注册：开关关闭时这个任务在业务上不存在，
 * 不应出现在「定时任务健康」面板里，否则面板会常年挂着一个「从未成功」的假告警。</p>
 *
 * <p>归档失败必须能被发现：它删的是审计记录，失败通常意味着磁盘不可写或数据库拒绝删除，
 * 这两种情况都需要人介入，而不是等磁盘满了才发现。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.audit.archive.enabled", havingValue = "true")
public class AuditArchiveJob {

    static final String JOB_NAME = "audit-archive";
    /** 与 {@code app.audit.archive.cron} 的默认值（每日 03:30）保持一致；改 cron 需同步这里。 */
    private static final long EXPECTED_INTERVAL_SECONDS = 86_400L;

    private final AuditArchiveService auditArchiveService;
    private final JobHealthRecorder jobHealthRecorder;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, EXPECTED_INTERVAL_SECONDS);
    }

    @Scheduled(cron = "${app.audit.archive.cron:0 30 3 * * *}")
    public void archive() {
        long startedAt = System.currentTimeMillis();
        try {
            AuditArchiveService.ArchiveResult result = auditArchiveService.archive();
            jobHealthRecorder.success(JOB_NAME, System.currentTimeMillis() - startedAt);
            if (result.archived() > 0) {
                log.info("审计归档完成 archived={} file={}", result.archived(), result.file());
            } else {
                log.info("审计归档无需处理：{}", result.reason());
            }
        } catch (Exception e) {
            jobHealthRecorder.failure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
            log.error("审计归档任务异常（已归档的记录不受影响，失败批次仍在库中）", e);
        }
    }
}
