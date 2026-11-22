package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 优惠券模板。
 *
 * <p>两种类型：满减（AMOUNT，直减 {@code valueFen}）与折扣（DISCOUNT，
 * {@code discountPercent=85} 表示 85 折，可配 {@code maxDiscountFen} 封顶）。
 * 折扣券没有封顶时，订单金额越大抵扣越多，这是运营最容易被薅的点，
 * 所以封顶字段默认建议配置。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("marketing_coupon")
public class MarketingCoupon extends BaseEntity {

    public static final String TYPE_AMOUNT = "AMOUNT";
    public static final String TYPE_DISCOUNT = "DISCOUNT";

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_PRODUCT = "PRODUCT";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STOPPED = "STOPPED";

    private String code;
    private String name;
    private String type;
    private Long thresholdFen;
    private Long valueFen;
    private Integer discountPercent;
    private Long maxDiscountFen;
    private Integer totalQuantity;
    private Integer perUserLimit;
    /** 已领取数量，领取时原子累加，用于控量防超发。 */
    private Integer claimedQuantity;
    private String scopeType;
    private Long scopeId;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String status;

    public boolean isDiscount() {
        return TYPE_DISCOUNT.equals(type);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
