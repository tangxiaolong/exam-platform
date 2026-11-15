package com.exam.platform.modules.org.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 额度发放流水：一次批量发放一行。
 *
 * <p>净额 = {@code consumedAmount - refundedAmount}，这个净额必须等于账户上的
 * {@code usedAmount}——两者对不上就说明有扣减没落流水、或有人手工改过账户余额。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org_quota_consume")
public class SysOrgQuotaConsume extends BaseEntity {

    private Long orgId;
    private Long quotaId;
    private String batchNo;
    private String entitlementType;
    private Long resourceId;
    private Integer targetCount;
    private Long amountPerUser;
    private Long consumedAmount;
    private Integer grantedCount;
    private Integer skippedCount;
    private Long refundedAmount;
    private Long operatorId;

    public long net() {
        long consumed = consumedAmount == null ? 0L : consumedAmount;
        long refunded = refundedAmount == null ? 0L : refundedAmount;
        return consumed - refunded;
    }
}
