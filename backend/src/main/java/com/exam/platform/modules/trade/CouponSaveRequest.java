package com.exam.platform.modules.trade;

import java.time.LocalDateTime;

/**
 * 管理端创建优惠券请求。
 *
 * <p>金额单位一律为分（与全站订单口径一致）；折扣百分比 85 表示 85 折。
 * 类型与范围的合法性由 {@link CouponService#adminCreate} 校验——这里只承载入参。</p>
 */
public record CouponSaveRequest(
        String code,
        String name,
        String type,
        Long thresholdFen,
        Long valueFen,
        Integer discountPercent,
        Long maxDiscountFen,
        Integer totalQuantity,
        Integer perUserLimit,
        String scopeType,
        Long scopeId,
        LocalDateTime validFrom,
        LocalDateTime validTo
) {
}
