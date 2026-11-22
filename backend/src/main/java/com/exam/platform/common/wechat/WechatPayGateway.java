package com.exam.platform.common.wechat;

import java.util.Map;

/**
 * 微信支付网关。抽象出来的目的：业务侧只表达「下单 / 查单 / 关单」，
 * 至于是走真实 v3 接口还是本地 mock，由 {@code app.wechat.mock} 决定，
 * 上层（TradeService、补偿任务）不需要任何 if。
 */
public interface WechatPayGateway {

    /** 是否 mock 实现（决定回调走验签还是走本地签名，也决定 dev 接口是否开放）。 */
    boolean mock();

    /** JSAPI 下单，返回 prepay_id 与可直接交给 wx.requestPayment 的参数。 */
    PrepayResult prepay(PrepayCommand command);

    /** 主动查单。这是「回调丢了」时唯一的补救手段。 */
    Transaction query(String outTradeNo);

    /** 关单（未支付且已超时）。已支付/已关闭的订单调用应幂等成功。 */
    void close(String outTradeNo);

    record PrepayCommand(String outTradeNo, String description, long amountFen, String openId) {
    }

    record PrepayResult(String prepayId, Map<String, String> payParams) {
    }

    /**
     * 查单/回调解出的交易事实。金额单位分，状态沿用微信的 trade_state。
     */
    record Transaction(String outTradeNo,
                       String transactionId,
                       String tradeState,
                       Long amountFen,
                       String currency,
                       String successTime) {

        public boolean success() {
            return "SUCCESS".equalsIgnoreCase(tradeState);
        }

        /** 微信侧已终结且不会再付：可以安全关闭本地订单。 */
        public boolean terminated() {
            return "CLOSED".equalsIgnoreCase(tradeState)
                    || "REVOKED".equalsIgnoreCase(tradeState)
                    || "PAYERROR".equalsIgnoreCase(tradeState);
        }
    }
}
