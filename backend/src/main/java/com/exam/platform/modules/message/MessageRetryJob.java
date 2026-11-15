package com.exam.platform.modules.message;

import com.exam.platform.common.job.JobHealthRecorder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 消息重试任务：投递失败的任务按退避时间自动重试。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.message.retry-enabled", havingValue = "true", matchIfMissing = true)
public class MessageRetryJob {

    static final String JOB_NAME = "message-retry";

    private final MessageService messageService;
    private final JobHealthRecorder jobHealthRecorder;

    /** 与 {@code app.message.retry-interval-ms} 同源，健康上报的预期周期不另写常量。 */
    @Value("${app.message.retry-interval-ms:60000}")
    private long intervalMs;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, Math.max(1L, intervalMs / 1000));
    }

    @Scheduled(fixedDelayString = "${app.message.retry-interval-ms:60000}")
    public void run() {
        long startedAt = System.currentTimeMillis();
        try {
            int count = messageService.retryDue(50);
            jobHealthRecorder.success(JOB_NAME, System.currentTimeMillis() - startedAt);
            if (count > 0) {
                log.info("消息重试完成，处理 {} 条", count);
            }
        } catch (Exception e) {
            // 失败只影响本轮：下一轮 scheduled 触发时仍会重试，所以这里不重新抛出
            jobHealthRecorder.failure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
            log.warn("消息重试任务异常", e);
        }
    }
}
