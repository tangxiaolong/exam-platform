package com.exam.platform.modules.trade.dto;

/** 下单支付返回：prepayInfo 交给小程序 wx.requestPayment 使用。 */
public record PaymentCreateResponse(
        String payNo,
        String orderNo,
        Long amountFen,
        String channel,
        String prepayInfo,
        String mockNotifyUrl
) {
}
