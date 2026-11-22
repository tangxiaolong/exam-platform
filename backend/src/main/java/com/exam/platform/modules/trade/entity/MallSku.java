package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品销售单元（SKU）。
 *
 * <p>同一商品可以有多个 SKU（月卡 / 季卡 / 年卡）。价格落在 SKU 上而不是商品上：
 * 订单明细的单价必须能从 SKU 取到确定值，否则「原价 99、活动价 79」这种情形
 * 会让下单金额与退款金额都对不上。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_sku")
public class MallSku extends BaseEntity {

    public static final String STATUS_ON_SALE = "ON_SALE";
    public static final String STATUS_OFF_SALE = "OFF_SALE";

    private Long productId;
    private String code;
    private String name;
    private Long priceFen;
    private Long originalPriceFen;
    /** 有效天数；为 null 时沿用商品配置。 */
    private Integer durationDays;
    private String status;
    private Integer sortNo;
}
