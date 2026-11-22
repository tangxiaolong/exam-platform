package com.exam.platform.modules.trade;

import java.time.LocalDateTime;

/**
 * 管理端领取记录视图：持券人 + 状态 + 核销信息。
 *
 * <p>运营查券时的第一个问题永远是「这张券谁领了、用没用」——
 * 只有券的启停而不见领取明细，是一本只记发行不记去向的账。</p>
 */
public record CouponGrantAdminView(
        Long grantId,
        Long userId,
        String username,
        String status,
        Long discountFen,
        Long orderId,
        LocalDateTime grantedAt,
        LocalDateTime usedAt
) {
}
