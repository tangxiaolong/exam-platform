package com.exam.platform.modules.org.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量授权入参：把一个权益额度发放给组织内所有（或指定）成员。
 *
 * @param orgId         目标组织
 * @param entitlementType MEMBER / QUESTION_BANK / COURSE / EXAM
 * @param resourceType  资源类型（PROJECT / COURSE ...），可为空
 * @param resourceId    资源 ID，可为空（空表示该类型通用额度）
 * @param amount        每人发放数量
 * @param expireAt      权益到期时间，可空
 * @param userIds       指定成员；为空则发给组织全部 ACTIVE 成员
 * @param remark        备注（如采购单号）
 */
public record OrgGrantRequest(
        Long orgId,
        String entitlementType,
        String resourceType,
        Long resourceId,
        Integer amount,
        LocalDateTime expireAt,
        List<Long> userIds,
        String remark
) {
}
