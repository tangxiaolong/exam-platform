package com.exam.platform.modules.org;

import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.modules.org.dto.QuotaBatchMismatch;
import com.exam.platform.modules.org.dto.QuotaMismatch;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 机构额度对账任务（两层）。
 *
 * <p>不一致按「失败」上报，而不是只打一行日志：额度对不上意味着机构账面上的可用量与
 * 实际发出去的东西不一致，可能少发了（机构投诉）也可能多发（平台亏钱），
 * 两种情况都需要人看，所以必须让它进「定时任务健康」告警。</p>
 *
 * <p>两层都要跑：账户级抓「扣减与记账不同步」，批次级抓「钱扣了但权益没到账」。
 * 后者不会影响前者，所以只跑账户级永远发现不了。</p>
 *
 * <p>每天一次全量对账的覆盖能力受限于批次级的时间窗（{@code lookback-days}）：
 * 窗口应大于任务周期，否则两次执行之间的空档没人核。默认 7 天对每日执行留了足够余量，
 * 同时把每日扫描量压在「最近一周」而不是「开服至今」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgQuotaReconJob {

    static final String JOB_NAME = "org-quota-recon";
    /** 与 {@code app.org-quota.recon-cron} 的默认值（每天 04:00）保持一致；改 cron 需同步这里。 */
    private static final long EXPECTED_INTERVAL_SECONDS = 86_400L;

    private final OrgQuotaReconService reconService;
    private final JobHealthRecorder jobHealthRecorder;

    /**
     * 批次级核查的天数窗口。
     *
     * <p>必须大于任务周期：若窗口小于执行间隔，两次执行之间的发放就永远没人核。
     * 但也不用无限大——窗口越长，每日扫描量越大，越容易撞上语句超时。</p>
     */
    @Value("${app.org-quota.recon-lookback-days:7}")
    private int lookbackDays;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, EXPECTED_INTERVAL_SECONDS);
    }

    @Scheduled(cron = "${app.org-quota.recon-cron:0 0 4 * * *}")
    public void reconcile() {
        long startedAt = System.currentTimeMillis();
        try {
            List<QuotaMismatch> accountMismatches = reconService.reconcileAccounts();
            List<QuotaBatchMismatch> batchMismatches = reconService.reconcileBatches(lookbackDays);
            long elapsed = System.currentTimeMillis() - startedAt;

            for (QuotaMismatch mismatch : accountMismatches) {
                log.warn("额度账户对账不一致 orgId={} quotaId={} type={} used={} ledger={} diff={}",
                        mismatch.orgId(), mismatch.quotaId(), mismatch.entitlementType(),
                        mismatch.usedAmount(), mismatch.ledgerNet(), mismatch.diff());
            }
            for (QuotaBatchMismatch mismatch : batchMismatches) {
                log.warn("额度批次对账不一致 batchNo={} orgId={} type={} flowNet={} ledger={} diff={}",
                        mismatch.batchNo(), mismatch.orgId(), mismatch.entitlementType(),
                        mismatch.flowNet(), mismatch.ledgerAmount(), mismatch.diff());
            }

            if (accountMismatches.isEmpty() && batchMismatches.isEmpty()) {
                jobHealthRecorder.success(JOB_NAME, elapsed);
                log.info("机构额度对账完成：账户与批次两层全部一致（批次窗口 {} 天）", lookbackDays);
                return;
            }
            jobHealthRecorder.failure(JOB_NAME, elapsed, new IllegalStateException(
                    "额度对账不一致：账户差异 " + accountMismatches.size()
                            + " 个，批次差异 " + batchMismatches.size() + " 个"));
        } catch (Exception e) {
            jobHealthRecorder.failure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
            log.error("机构额度对账任务异常", e);
        }
    }
}
