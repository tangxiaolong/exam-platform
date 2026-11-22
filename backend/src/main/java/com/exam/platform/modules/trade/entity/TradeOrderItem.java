package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单明细。
 *
 * <p>名称与单价都是下单时的<b>快照</b>：商品改名或改价后，历史订单与退款金额不能跟着变。
 * {@code refundedAmountFen} 记录该行已退金额，用于「多次部分退款」的累计校验——
 * 没有它，同一行可以被重复退到超出实付。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_order_item")
public class TradeOrderItem extends BaseEntity {

    private Long orderId;
    private Long productId;
    private Long skuId;
    private String productName;
    private String skuName;
    private Long unitPriceFen;
    private Integer quantity;
    private Long amountFen;
    private Long refundedAmountFen;
    private Integer durationDays;

    /** 该行还能退多少（分）。 */
    public long refundableFen() {
        long refunded = refundedAmountFen == null ? 0L : refundedAmountFen;
        long amount = amountFen == null ? 0L : amountFen;
        return Math.max(0L, amount - refunded);
    }
}
