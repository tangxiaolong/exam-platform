package com.exam.platform.modules.trade.dto;

/**
 * 创建订单入参。金额一律由服务端按商品/ SKU 价格快照计算，不接受客户端传价。
 *
 * <p>{@code skuId} 为空时回退到商品的默认 SKU（兼容只传 productId 的老调用），
 * {@code couponGrantId} 为空表示不使用优惠券。</p>
 */
public record CreateOrderRequest(Long productId, Long skuId, Integer quantity,
                                 Long couponGrantId, String remark) {
}
