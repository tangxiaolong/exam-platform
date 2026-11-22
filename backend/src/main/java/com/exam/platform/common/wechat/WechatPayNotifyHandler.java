package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;

import java.util.Map;

/**
 * 支付回调的解析入口。
 *
 * <p>单独抽出来是因为「验签 + 解密」和「落账」是两件必须分离的事：
 * 前者只依赖微信的密钥与证书，后者依赖订单库。分开后 mock 模式也能复用同一段落账逻辑，
 * 而不会出现「本地能过、线上验签挂」的两套代码。</p>
 */
public interface WechatPayNotifyHandler {

    /**
     * 校验并解析回调。
     *
     * @param headers 回调请求头（大小写不敏感访问）
     * @param rawBody 原始报文，必须与验签时完全一致
     * @throws BizException 验签失败、解密失败、事件类型不符
     */
    Notify parse(Map<String, String> headers, String rawBody);

    /** 解出的一笔交易事实。 */
    record Notify(String payNo, String transactionId, Long amountFen, String currency, String tradeState) {
    }

    /** mock 模式下不接收真实回调：明确报错好过静默返回成功。 */
    class Disabled implements WechatPayNotifyHandler {
        @Override
        public Notify parse(Map<String, String> headers, String rawBody) {
            throw new BizException("PAY_V3_NOT_ENABLED",
                    "当前为 mock 支付模式（app.wechat.mock=true），不接受真实微信支付回调");
        }
    }
}
