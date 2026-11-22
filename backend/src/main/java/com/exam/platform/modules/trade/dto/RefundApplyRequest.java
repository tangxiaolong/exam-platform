package com.exam.platform.modules.trade.dto;

/**
 * 退款申请入参。
 *
 * <p>{@code orderItemId} 为空表示整单全额退款（首期兼容行为）；
 * 指定明细则按该明细的可退金额做部分退款，{@code quantity} 为本次退掉的数量。</p>
 */
public record RefundApplyRequest(String reason, Long orderItemId, Integer quantity) {
}
