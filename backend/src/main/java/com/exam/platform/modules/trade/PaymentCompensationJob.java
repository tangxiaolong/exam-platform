package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.job.JobHealthRecorder;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.wechat.WechatPayGateway;
import com.exam.platform.common.wechat.WechatPayProperties;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import com.exam.platform.modules.trade.entity.TradePayment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付补偿任务：超时关单 + 主动查单。
 *
 * <p>为什么必须有这个任务：支付回调是<b>异步且可能丢失</b>的（网络抖动、发版重启、
 * 验签配置错误被我们拒掉、微信重试耗尽）。只等回调意味着「用户付了钱，订单永远是待支付」，
 * 而这种问题通常由用户投诉才发现。</p>
 *
 * <p>判定顺序刻意是「先查单再关单」：如果反过来先关单，一笔其实已经支付成功的单会被关掉，
 * 随后到达的回调变成「已关单却收到支付成功」，只能人工退款。先查单就没有这个窗口。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.wechat.pay.compensation-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentCompensationJob {

    static final String JOB_NAME = "payment-compensation";

    private final TradeService tradeService;
    private final WechatPayGateway payGateway;
    private final WechatPayProperties properties;
    private final SysTenantMapper tenantMapper;
    private final JobHealthRecorder jobHealthRecorder;

    @PostConstruct
    void registerHealth() {
        jobHealthRecorder.register(JOB_NAME, properties.getCompensationExpectedIntervalSeconds());
    }

    @Scheduled(cron = "${app.wechat.pay.compensation-cron:0 */10 * * * *}")
    public void compensate() {
        long startedAt = System.currentTimeMillis();
        List<SysTenant> tenants = tenantMapper.selectList(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getStatus, "ENABLED"));
        int settled = 0;
        int closed = 0;
        int failed = 0;
        Exception lastError = null;

        LocalDateTime deadline = LocalDateTime.now().minusMinutes(properties.getUnpaidTimeoutMinutes());
        for (SysTenant tenant : tenants) {
            // 支付单查询受租户插件约束，任务线程没有登录态，必须显式切租户上下文
            UserContext.set(new LoginUser(0L, "system", "支付补偿任务", tenant.getId(), LoginUser.ROLE_ADMIN));
            try {
                int[] result = compensateTenant(deadline);
                settled += result[0];
                closed += result[1];
            } catch (Exception e) {
                failed++;
                lastError = e;
                log.warn("支付补偿失败 tenantId={} code={}", tenant.getId(), tenant.getCode(), e);
            } finally {
                UserContext.clear();
            }
        }

        long cost = System.currentTimeMillis() - startedAt;
        if (failed == 0) {
            jobHealthRecorder.success(JOB_NAME, cost);
            log.info("支付补偿完成：补单 {} 笔，关单 {} 笔，租户 {}，耗时 {}ms",
                    settled, closed, tenants.size(), cost);
        } else {
            jobHealthRecorder.failure(JOB_NAME, cost, lastError);
            log.warn("支付补偿部分失败：失败租户 {}，补单 {} 笔，关单 {} 笔，耗时 {}ms",
                    failed, settled, closed, cost);
        }
    }

    private int[] compensateTenant(LocalDateTime deadline) {
        List<TradePayment> candidates = tradeService.findStaleCreatedPayments(deadline,
                properties.getCompensationBatchSize());
        int settled = 0;
        int closed = 0;
        for (TradePayment payment : candidates) {
            try {
                WechatPayGateway.Transaction tx = payGateway.query(payment.getPayNo());
                if (tx != null && tx.success()) {
                    // 回调丢了但钱收了：走与回调完全相同的落账路径
                    if (tradeService.settleByQuery(payment, tx)) {
                        settled++;
                        log.info("主动查单补单成功 payNo={} txn={}", payment.getPayNo(), tx.transactionId());
                    }
                    continue;
                }
                if (tx != null && tx.terminated()) {
                    // 微信侧已终结，直接对齐本地状态
                    tradeService.closeUnpaid(payment.getPayNo(), "CHANNEL_" + tx.tradeState());
                    closed++;
                    continue;
                }
                closeExpired(payment);
                closed++;
            } catch (Exception e) {
                // 单笔异常不能中断整个租户：否则一笔脏数据会让后面的补偿永远轮不到
                log.warn("补偿单笔失败 payNo={}：{}", payment.getPayNo(), e.getMessage());
            }
        }
        return new int[]{settled, closed};
    }

    private void closeExpired(TradePayment payment) {
        try {
            payGateway.close(payment.getPayNo());
        } catch (Exception e) {
            // 关单失败也要关本地：否则本地会永远停在待支付，且每轮补偿都重复处理这一笔
            log.warn("渠道关单失败，仍关闭本地支付单 payNo={}：{}", payment.getPayNo(), e.getMessage());
        }
        tradeService.closeUnpaid(payment.getPayNo(), "TIMEOUT");
    }
}
