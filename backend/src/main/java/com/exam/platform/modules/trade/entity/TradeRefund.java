package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 退款单。状态机：APPLIED → SUCCESS / REJECTED / FAILED。
 *
 * <p>首期为「整单全额退款」：金额取订单实付金额的快照；
 * 部分退款、多笔退款需要按订单明细拆单，属于 P2 范围。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_refund")
public class TradeRefund extends BaseEntity {

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FAILED = "FAILED";

    private String refundNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private String payNo;
    private Long amountFen;
    private String reason;
    private String status;
    private String auditRemark;
    private Long auditedBy;
    private LocalDateTime appliedAt;
    private LocalDateTime auditedAt;
    private LocalDateTime refundedAt;
}
