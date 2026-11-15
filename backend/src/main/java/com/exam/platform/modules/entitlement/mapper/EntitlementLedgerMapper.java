package com.exam.platform.modules.entitlement.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.platform.modules.entitlement.entity.EntitlementLedger;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface EntitlementLedgerMapper extends BaseMapper<EntitlementLedger> {

    /**
     * 组织批量发放产生的权益流水（跨租户，按时间窗）。
     *
     * <p>只取 {@code id}/{@code event_id}/{@code change_amount} 三列：这里要做的是「按批次求和」，
     * 不关心发给谁。跨租户的必要性同额度对账——任务跑在无登录态线程里，
     * 只看平台租户会漏掉其它租户的发放记录。</p>
     *
     * <p>限定时间窗是为了让对账能长期跑下去：台账是只增不删的大表，全表扫迟早撞上
     * {@code default-statement-timeout}，而超时的对账等于没对账。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, event_id, change_amount FROM entitlement_ledger "
            + "WHERE deleted = 0 AND event_id LIKE 'ORG-%' "
            + "AND created_at >= #{from} AND created_at < #{to} ORDER BY id ASC")
    List<EntitlementLedger> selectOrgGrantEventsBetween(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to);

    /** 全部组织发放流水（跨租户）。仅供排障与全量核对，日常对账走时间窗版本。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, event_id, change_amount FROM entitlement_ledger "
            + "WHERE deleted = 0 AND event_id LIKE 'ORG-%' ORDER BY id ASC")
    List<EntitlementLedger> selectOrgGrantEvents();
}
