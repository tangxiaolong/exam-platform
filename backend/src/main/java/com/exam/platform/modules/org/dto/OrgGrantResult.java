package com.exam.platform.modules.org.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量授权结果。
 *
 * @param batchNo        授权批次号（用于追溯与幂等）
 * @param orgId          目标组织
 * @param targetCount    目标成员数
 * @param grantedCount   实际发放成功数
 * @param skippedCount   跳过数（重复事件或无有效成员）
 * @param skippedUserIds 被跳过的成员
 */
public record OrgGrantResult(
        String batchNo,
        Long orgId,
        int targetCount,
        int grantedCount,
        int skippedCount,
        List<Long> skippedUserIds,
        String entitlementType,
        Long resourceId,
        LocalDateTime expireAt
) {
}
