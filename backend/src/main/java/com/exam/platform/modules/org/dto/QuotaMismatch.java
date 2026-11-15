package com.exam.platform.modules.org.dto;

/**
 * 额度对账差异。
 *
 * <p>「账户上的已用数量」与「发放流水净额」应当永远相等；不相等意味着有扣减没落流水、
 * 有人手工改过余额、或退款没生效。{@code diff = usedAmount - ledgerNet}，
 * 正数表示账户记多了消耗（相当于凭空少了额度），负数表示记少了。</p>
 */
public record QuotaMismatch(
        Long orgId,
        Long quotaId,
        String entitlementType,
        long usedAmount,
        long ledgerNet,
        long diff
) {
}
