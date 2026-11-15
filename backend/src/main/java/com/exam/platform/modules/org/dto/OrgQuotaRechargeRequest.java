package com.exam.platform.modules.org.dto;

import java.time.LocalDateTime;

/**
 * 平台给机构充值额度。
 *
 * @param entitlementType MEMBER / QUESTION_BANK / COURSE / EXAM
 * @param resourceId      资源 ID；为空表示该类型的通用额度
 * @param amount          充值份数（必须大于 0）
 * @param expireAt        额度有效期；为空表示长期有效。已有账户续充时只能把期限往后推
 * @param remark          备注（如采购单号、合同号）
 */
public record OrgQuotaRechargeRequest(
        String entitlementType,
        Long resourceId,
        Long amount,
        LocalDateTime expireAt,
        String remark
) {
}
