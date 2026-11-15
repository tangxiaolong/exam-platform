package com.exam.platform.modules.trade.dto;

/**
 * 微信支付通知入参（简化模型）。
 *
 * <p>真实接入时改为解析微信 V3 平台证书加密报文，并校验
 * appid / 商户号 / 币种 / 交易状态，本模型保留了同等校验位。</p>
 */
public record WechatNotifyRequest(
        String outTradeNo,
        String transactionId,
        Long amountFen,
        String currency,
        String tradeState,
        String sign
) {
}
