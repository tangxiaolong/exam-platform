package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单。状态机：CREATED → PENDING_PAYMENT → PAID → COMPLETED，
 * 异常分支 CLOSED / REFUNDING / REFUNDED。
 *
 * <p>价格字段为下单时读取服务端商品价格形成的快照，不接受客户端传入金额。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_order")
public class TradeOrder extends BaseEntity {

    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    private String orderNo;
    private Long userId;
    private Long productId;
    private String productName;
    private Integer quantity;
    private Long totalAmountFen;
    private Long discountAmountFen;
    private Long payAmountFen;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private String remark;
}
