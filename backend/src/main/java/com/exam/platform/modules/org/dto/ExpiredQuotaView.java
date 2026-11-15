package com.exam.platform.modules.org.dto;

import java.time.LocalDateTime;

/**
 * 已过期的额度账户（含未用余额）。
 *
 * <p>只读视图：过期账户不会被删除——删掉账户会让机构退化成「没有额度账户」，
 * 而「没有账户」在当前实现里等于不限额。所以过期只影响「能不能继续消耗」，
 * 账户本身保留下来供对账与追溯。</p>
 */
public record ExpiredQuotaView(
        Long orgId,
        String orgName,
        String entitlementType,
        Long resourceId,
        long balance,
        LocalDateTime expireAt
) {
}
