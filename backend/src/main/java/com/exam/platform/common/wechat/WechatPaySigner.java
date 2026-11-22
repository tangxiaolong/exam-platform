package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 微信支付 v3 的请求签名。
 *
 * <p>v3 用商户私钥对 {@code 方法\nURL\ntimestamp\nnonce\nbody\n} 做 RSA-SHA256 签名，
 * 把结果连同商户证书序列号放进 {@code Authorization} 头。签名串里的换行不能省，
 * 少一个 \n 或 URL 里 query 顺序变了都会验签失败——所以这里把「构造待签名串」和
 * 「拼 Authorization 头」放在一起，避免调用方各自拼错。</p>
 */
public final class WechatPaySigner {

    private static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    private final String mchId;
    private final String serialNo;
    private final PrivateKey privateKey;

    public WechatPaySigner(String mchId, String serialNo, PrivateKey privateKey) {
        this.mchId = mchId;
        this.serialNo = serialNo;
        this.privateKey = privateKey;
    }

    /**
     * 加载 PKCS#8 私钥（apiclient_key.pem 的内容）。
     *
     * @param pem PEM 文本，允许带 \n 转义或首尾的 BEGIN/END 行
     */
    public static PrivateKey loadPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new BizException("PAY_KEY_MISSING", "未配置商户支付私钥");
        }
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new BizException("PAY_KEY_INVALID", "商户支付私钥无法解析：" + e.getMessage());
        }
    }

    /** 对任意消息做 RSA-SHA256 签名并 Base64。也用于小程序 wx.requestPayment 的 paySign。 */
    public String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("微信支付签名失败", e);
        }
    }

    /**
     * 生成请求头。
     *
     * @param method       GET / POST
     * @param canonicalUrl 带 query 的路径，如 {@code /v3/pay/transactions/jsapi}
     * @param body         请求体，GET 传空串
     */
    public String authorization(String method, String canonicalUrl, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        return SCHEMA + " mchid=\"" + mchId + "\",nonce_str=\"" + nonce + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + serialNo + "\",signature=\"" + sign(message) + "\"";
    }
}
