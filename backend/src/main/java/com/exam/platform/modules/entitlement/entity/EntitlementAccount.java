package com.exam.platform.modules.entitlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 权益账户：同一用户 + 权益类型 + 资源维度聚合余量。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("entitlement_account")
public class EntitlementAccount extends BaseEntity {

    public static final String TYPE_MEMBER = "MEMBER";
    public static final String TYPE_QUESTION_BANK = "QUESTION_BANK";
    public static final String TYPE_COURSE = "COURSE";
    public static final String TYPE_EXAM = "EXAM";

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_PART_USED = "PART_USED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long userId;
    private String entitlementType;
    private String resourceType;
    private Long resourceId;
    private Integer totalAmount;
    private Integer usedAmount;
    private String status;
    private LocalDateTime expireAt;
    private Long sourceOrderId;
}
