package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 商品：会员、题库包、课程、套餐。金额统一以「分」为单位的整数存储。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_product")
public class MallProduct extends BaseEntity {

    public static final String TYPE_MEMBER = "MEMBER";
    public static final String TYPE_QUESTION_BANK = "QUESTION_BANK";
    public static final String TYPE_COURSE = "COURSE";
    public static final String TYPE_PACKAGE = "PACKAGE";

    public static final String STATUS_ON_SALE = "ON_SALE";
    public static final String STATUS_OFF_SALE = "OFF_SALE";

    private String code;
    private String name;
    private String type;
    private String description;
    private String coverUrl;
    private Long priceFen;
    private Long originalPriceFen;
    private Integer validityDays;
    private String resourceType;
    private Long resourceId;
    private String status;
    private Integer sortNo;
}
