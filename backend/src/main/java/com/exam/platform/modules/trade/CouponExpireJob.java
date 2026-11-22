package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券到期失效任务：把「有效期已过」但仍未使用的持券记录翻成 EXPIRED。
 *
 * <p>没有这个任务，过期券在「我的券」里会永远显示「未使用」——下单时再被
 * COUPON_EXPIRED 拒绝. 状态与事实分叉，客服的多数券类工单都从这种分叉开始.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.trade.coupon-expire-enabled", havingValue = "true", matchIfMissing = true)
public class CouponExpireJob {

    static final String JOB_NAME = "coupon-expire";

    /** 每 10 分钟跑一次；健康检查按 20 分钟无心跳才告警（容忍一轮抖动）。 */
    private static final long EXPECTED_INTERVAL_SECONDS = 1200L;

    private final CouponService couponService;
    private final SysTenantMapper tenantMapper;
    private final JobHealthRecorder jobHealthRecorder;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, EXPECTED_INTERVAL_SECONDS);
    }

    @Scheduled(cron = "${app.trade.coupon-expire-cron:0 */10 * * * *}")
    public void run() {
        long startedAt = System.currentTimeMillis();
        List<SysTenant> tenants = tenantMapper.selectList(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getStatus, "ENABLED"));
        int expired = 0;
        Exception lastError = null;
        for (SysTenant tenant : tenants) {
            // 券表查询受租户插件约束，任务线程没有登录态，必须显式切租户上下文
            UserContext.set(new LoginUser(0L, "system", "优惠券到期任务", tenant.getId(), LoginUser.ROLE_ADMIN));
            try {
                expired += couponService.expireOverdue();
            } catch (Exception e) {
                lastError = e;
                log.warn("优惠券到期任务失败 tenantId={} code={}", tenant.getId(), tenant.getCode(), e);
            } finally {
                UserContext.clear();
            }
        }
        long cost = System.currentTimeMillis() - startedAt;
        if (lastError == null) {
            jobHealthRecorder.success(JOB_NAME, cost);
            if (expired > 0) {
                log.info("优惠券到期失效 {} 张，租户 {}，耗时 {}ms", expired, tenants.size(), cost);
            }
        } else {
            jobHealthRecorder.failure(JOB_NAME, cost, lastError);
        }
    }
}
