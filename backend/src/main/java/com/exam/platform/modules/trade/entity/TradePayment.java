package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 支付单。支付成功以支付平台回调（验签+金额核对）为准，前端结果仅作展示。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_payment")
public class TradePayment extends BaseEntity {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long orderId;
    private String orderNo;
    private String payNo;
    private String channel;
    private Long amountFen;
    private String status;
    private String transactionId;
    private String prepayJson;
    private LocalDateTime paidAt;
}
