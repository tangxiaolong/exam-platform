package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户持有的优惠券。
 *
 * <p>四个状态构成一条完整的生命周期：领取 UNUSED → 下单锁定 LOCKED → 支付成功核销 USED，
 * 订单关闭/超时未付则回滚成 UNUSED。少了 LOCKED 这一步，同一张券可以同时抵两笔订单。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("marketing_coupon_grant")
public class MarketingCouponGrant extends BaseEntity {

    public static final String STATUS_UNUSED = "UNUSED";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private Long couponId;
    private Long userId;
    private String status;
    private Long discountFen;
    private Long orderId;
    private LocalDateTime grantedAt;
    private LocalDateTime usedAt;
}
