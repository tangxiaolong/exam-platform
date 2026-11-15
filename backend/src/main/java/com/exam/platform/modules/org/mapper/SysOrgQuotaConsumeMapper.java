package com.exam.platform.modules.org.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.platform.modules.org.entity.SysOrgQuotaConsume;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface SysOrgQuotaConsumeMapper extends BaseMapper<SysOrgQuotaConsume> {

    /**
     * 时间窗内的发放流水（跨租户）：对账任务用。
     *
     * <p>忽略租户拦截：对账任务跑在无登录态的系统线程里（默认平台租户），
     * 不忽略的话只能对到平台租户的流水，其它租户永远「对不上」。</p>
     *
     * <p>按 created_at 限定时间窗而不是全表扫：流水只增不删，全量扫的代价随运营时间线性增长，
     * 最终会因为 {@code default-statement-timeout} 超时让对账整体失败——而失败的对账等于没对账。
     * 时间窗由调用方给定，跨度覆盖「上一次成功对账以来」即可。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_org_quota_consume WHERE deleted = 0 "
            + "AND created_at >= #{from} AND created_at < #{to} ORDER BY id ASC")
    List<SysOrgQuotaConsume> selectCrossTenantBetween(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

    /**
     * 全量按账户聚合的流水净额（含退款）。
     *
     * <p>账户级的 {@code used_amount} 是<b>累计值</b>，不能和时间窗内的净额直接比——
     * 截断窗口会把「窗口之前发生的消耗」误报成差异。所以账户级对账用全量聚合，
     * 但只发一次 GROUP BY 查询，而不是「列账户 + 逐个 SUM」的 N+1。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT quota_id AS quotaId, "
            + "COALESCE(SUM(consumed_amount - refunded_amount), 0) AS net "
            + "FROM sys_org_quota_consume WHERE deleted = 0 GROUP BY quota_id")
    List<QuotaNetRow> sumNetGrouped();

    /** 全部发放流水（跨租户）：对账任务要用，理由见 {@link #selectCrossTenantBetween}。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_org_quota_consume WHERE deleted = 0 ORDER BY id ASC")
    List<SysOrgQuotaConsume> selectAllCrossTenant();
}
