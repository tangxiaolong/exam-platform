package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;
import java.util.Map;
import java.util.function.Function;

/**
 * 微信支付 v3 回调的真实解析实现：验签 → 解密 → 归属校验。
 *
 * <p>顺序不能颠倒：先验签再解密。反过来等于「先花 CPU 解一份来源不明的密文」，
 * 而且解密成功会让实现者误以为报文可信。</p>
 *
 * <p>归属校验（appid / mchid）经常被漏掉：验证签名只证明报文来自微信，
 * 不证明它属于<b>我们这个商户</b>。同一平台多个商户号共用回调地址时，
 * 少了这一步会把别人的订单当成自己的。</p>
 */
@Slf4j
public class V3WechatPayNotifyHandler implements WechatPayNotifyHandler {

    private static final String EVENT_SUCCESS = "TRANSACTION.SUCCESS";

    private final WechatPayVerifier verifier;
    private final Function<String, PublicKey> publicKeyResolver;
    private final String expectedAppId;
    private final String expectedMchId;

    public V3WechatPayNotifyHandler(WechatPayVerifier verifier,
                                    Function<String, PublicKey> publicKeyResolver,
                                    String expectedAppId,
                                    String expectedMchId) {
        this.verifier = verifier;
        this.publicKeyResolver = publicKeyResolver;
        this.expectedAppId = expectedAppId;
        this.expectedMchId = expectedMchId;
    }

    @Override
    public Notify parse(Map<String, String> headers, String rawBody) {
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");
        String serial = header(headers, "Wechatpay-Serial");

        verifier.verify(timestamp, nonce, signature, rawBody, publicKeyResolver.apply(serial));

        NotifyEnvelope envelope = Json.read(rawBody, NotifyEnvelope.class);
        if (envelope == null || envelope.resource() == null) {
            throw new BizException("PAY_NOTIFY_MALFORMED", "回调报文缺少 resource");
        }
        if (!EVENT_SUCCESS.equals(envelope.eventType())) {
            log.warn("收到非成功事件回调 eventType={}，交由上层判定", envelope.eventType());
        }

        String plain = verifier.decrypt(envelope.resource().associatedData(),
                envelope.resource().nonce(), envelope.resource().ciphertext());
        TransactionPayload payload = Json.read(plain, TransactionPayload.class);
        if (payload == null || payload.outTradeNo() == null) {
            throw new BizException("PAY_NOTIFY_MALFORMED", "回调密文内容无法解析");
        }

        // 签名只证明「来自微信」，还要证明「是我们的单」
        if (expectedMchId != null && payload.mchId() != null && !expectedMchId.equals(payload.mchId())) {
            throw new BizException("PAY_NOTIFY_FOREIGN", "回调商户号与本平台不一致：" + payload.mchId());
        }
        if (expectedAppId != null && payload.appId() != null && !expectedAppId.equals(payload.appId())) {
            throw new BizException("PAY_NOTIFY_FOREIGN", "回调 appid 与本平台不一致：" + payload.appId());
        }

        Amount amount = payload.amount();
        Long amountFen = amount == null || amount.total() == null ? null : amount.total();
        String currency = amount == null ? null : amount.currency();
        return new Notify(payload.outTradeNo(), payload.transactionId(), amountFen, currency, payload.tradeState());
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(e -> name.equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NotifyEnvelope(@JsonProperty("id") String id,
                          @JsonProperty("event_type") String eventType,
                          @JsonProperty("resource_type") String resourceType,
                          @JsonProperty("resource") EncryptedResource resource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EncryptedResource(@JsonProperty("algorithm") String algorithm,
                             @JsonProperty("ciphertext") String ciphertext,
                             @JsonProperty("nonce") String nonce,
                             @JsonProperty("associated_data") String associatedData) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionPayload(@JsonProperty("out_trade_no") String outTradeNo,
                              @JsonProperty("transaction_id") String transactionId,
                              @JsonProperty("trade_state") String tradeState,
                              @JsonProperty("appid") String appId,
                              @JsonProperty("mchid") String mchId,
                              @JsonProperty("amount") Amount amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Amount(@JsonProperty("total") Long total, @JsonProperty("currency") String currency) {
    }
}
