package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 支付回调原始流水：先落库再处理，便于对账与问题追溯。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_payment_notify")
public class TradePaymentNotify extends BaseEntity {

    private String payNo;
    private String channel;
    private String rawBody;
    private String verifyResult;
    private Integer processed;
}
