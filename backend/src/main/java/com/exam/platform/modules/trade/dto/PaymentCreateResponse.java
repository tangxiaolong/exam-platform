package com.exam.platform.modules.trade.dto;

import java.util.Map;

/**
 * 下单支付返回。
 *
 * <p>{@code payParams} 是可直接展开给 {@code wx.requestPayment} 的参数
 * （appId / timeStamp / nonceStr / package / signType / paySign）；
 * mock 模式下它只带调试信息，前端据此走本地模拟回调。</p>
 */
public record PaymentCreateResponse(
        String payNo,
        String orderNo,
        Long amountFen,
        String channel,
        String prepayInfo,
        String mockNotifyUrl,
        Map<String, String> payParams
) {
}
