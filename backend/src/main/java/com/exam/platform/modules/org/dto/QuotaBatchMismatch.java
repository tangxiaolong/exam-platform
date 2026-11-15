package com.exam.platform.modules.org.dto;

/**
 * 批次级对账差异：发放流水净额 vs 权益台账实际到账。
 *
 * <p>这一层抓的是「流水记了、钱扣了，但权益没真正发到成员手上」——
 * 账户级对账看不出来（两边都是同一个批次号），只有把流水的净额和
 * {@code entitlement_ledger} 里该批次的实际入账金额对一遍才会暴露。</p>
 */
public record QuotaBatchMismatch(
        String batchNo,
        Long orgId,
        Long quotaId,
        String entitlementType,
        long flowNet,
        long ledgerAmount,
        long diff
) {
}
