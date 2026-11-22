package com.exam.platform.modules.trade;

import java.time.LocalDateTime;

/**
 * 我的券视图：持券记录 + 券面信息合并返回。
 *
 * <p>考生端「我的券」列表要展示券名称与优惠内容，而 grant 记录只有 couponId——
 * 让前端按 id 反查券表意味着每个列表页配一次查询，且已停发的券仍应正常展示。
 * 合并在这里一次完成。</p>
 */
public record CouponGrantView(
        Long grantId,
        Long couponId,
        String couponName,
        String type,
        Long thresholdFen,
        Long valueFen,
        Integer discountPercent,
        Long maxDiscountFen,
        String status,
        Long discountFen,
        LocalDateTime grantedAt,
        LocalDateTime usedAt,
        LocalDateTime validTo
) {
}
