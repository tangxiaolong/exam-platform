package com.exam.platform.modules.trade.dto;

/** 创建订单入参：金额由服务端按商品价格快照计算，不接受客户端传价格。 */
public record CreateOrderRequest(Long productId, Integer quantity, String remark) {
}
