package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import com.exam.platform.modules.trade.entity.TradeReconReport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日对账任务。
 *
 * <p>逐租户执行而不是一次性跑全库：对账 SQL 依赖 {@link UserContext} 的租户上下文
 * （租户插件按它追加 tenant_id），任务线程没有登录态，必须显式逐租户切换上下文，
 * 否则会以平台租户口径跑出错误结论。</p>
 *
 * <p>健康上报按「整轮」口径记账：只要有一个租户失败，这一轮就记为失败。
 * 单租户脏数据让整轮失败的原因是——对账停摆或长期算错，比某个租户少算一天严重得多，
 * 必须有人来看；具体是哪个租户失败看 warn 日志里的 tenantId。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    static final String JOB_NAME = "reconciliation";
    /** 与 {@code app.reconciliation.cron} 的默认值（每日 02:00）保持一致；改 cron 需同步这里。 */
    private static final long EXPECTED_INTERVAL_SECONDS = 86_400L;

    private final ReconciliationService reconciliationService;
    private final SysTenantMapper tenantMapper;
    private final JobHealthRecorder jobHealthRecorder;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, EXPECTED_INTERVAL_SECONDS);
    }

    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}")
    public void dailyReconcile() {
        long startedAt = System.currentTimeMillis();
        List<SysTenant> tenants = tenantMapper.selectList(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getStatus, "ENABLED"));
        int failedTenants = 0;
        Exception lastError = null;
        for (SysTenant tenant : tenants) {
            // 任务线程无登录态，用一个系统身份承载租户上下文；不参与鉴权，只用于租户过滤
            UserContext.set(new LoginUser(0L, "system", "对账任务", tenant.getId(), LoginUser.ROLE_ADMIN));
            try {
                reconciliationService.run(TradeReconReport.SCOPE_SCHEDULED);
            } catch (Exception e) {
                // 单租户失败不能中断其它租户，否则一个脏数据就能让全平台停止对账
                failedTenants++;
                lastError = e;
                log.warn("定时对账失败 tenantId={} code={}", tenant.getId(), tenant.getCode(), e);
            } finally {
                UserContext.clear();
            }
        }
        long cost = System.currentTimeMillis() - startedAt;
        if (failedTenants == 0) {
            jobHealthRecorder.success(JOB_NAME, cost);
            log.info("定时对账完成，租户数={} 耗时={}ms", tenants.size(), cost);
        } else {
            jobHealthRecorder.failure(JOB_NAME, cost, lastError);
            log.warn("定时对账部分失败，失败租户={}/{} 耗时={}ms", failedTenants, tenants.size(), cost);
        }
    }
}
