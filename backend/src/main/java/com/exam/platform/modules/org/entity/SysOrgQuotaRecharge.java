package com.exam.platform.modules.org.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 额度充值流水：一笔充值一行，用于按采购单/合同对账。
 *
 * <p>与账户上的 {@code totalAmount} 是「明细」与「汇总」的关系——两者之和必须相等，
 * 不一致就说明有充值没落流水（或有人手工改过账户），这类偏差要能被查出来。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org_quota_recharge")
public class SysOrgQuotaRecharge extends BaseEntity {

    private Long orgId;
    private Long quotaId;
    private String entitlementType;
    private Long resourceId;
    private Long amount;
    /** 本次充值指定的有效期；null 表示未指定（不改变账户原有期限）。 */
    private LocalDateTime expireAt;
    private String remark;
    private Long operatorId;
}
