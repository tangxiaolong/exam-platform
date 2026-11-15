package com.exam.platform.modules.entitlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 权益流水。幂等键：(tenant_id, event_id, entitlement_type, resource_id)，
 * 同一支付事件重复回调不会重复发放权益。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("entitlement_ledger")
public class EntitlementLedger extends BaseEntity {

    public static final String CHANGE_GRANT = "GRANT";
    public static final String CHANGE_USE = "USE";
    public static final String CHANGE_REVOKE = "REVOKE";

    private Long userId;
    private Long accountId;
    private String entitlementType;
    private Long resourceId;
    private String changeType;
    private Integer changeAmount;
    private Integer balanceAfter;
    private Long sourceOrderId;
    private String eventId;
    private LocalDateTime expireAt;
}
