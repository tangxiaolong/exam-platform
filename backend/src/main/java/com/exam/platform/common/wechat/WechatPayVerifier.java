package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 微信支付 v3 的验签与报文解密。
 *
 * <p>两件事分开做、都必须做：</p>
 * <ol>
 *   <li><b>验签</b>：证明报文确实来自微信支付（用平台证书公钥对
 *       {@code timestamp\nnonce\nbody\n} 做 RSA-SHA256 校验），并限制时间戳窗口，
 *       否则攻击者拿到一份有效报文可以无限期重放；</li>
 *   <li><b>解密</b>：回调里的交易详情是 AEAD_AES_256_GCM 加密的，
 *       用 APIv3 密钥解，AAD 必须原样传，否则解密一定失败。</li>
 * </ol>
 *
 * <p>没有验签就解密、或者验签了却不校验时间戳，都是常见的「看起来接好了」的错法。</p>
 */
public class WechatPayVerifier {

    /** 微信 APIv3 密钥固定 32 字节。 */
    private static final int API_V3_KEY_LENGTH = 32;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] apiV3Key;
    private final long toleranceSeconds;
    private final Clock clock;

    public WechatPayVerifier(String apiV3Key, long toleranceSeconds) {
        this(apiV3Key, toleranceSeconds, Clock.systemUTC());
    }

    public WechatPayVerifier(String apiV3Key, long toleranceSeconds, Clock clock) {
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != API_V3_KEY_LENGTH) {
            throw new BizException("PAY_APIV3_KEY_INVALID",
                    "APIv3 密钥必须是 " + API_V3_KEY_LENGTH + " 字节，当前配置长度不符");
        }
        this.apiV3Key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    /**
     * 校验微信支付报文签名。
     *
     * @throws BizException 时间戳超窗、签名缺失或不匹配
     */
    public void verify(String timestamp, String nonce, String signature, String body, PublicKey publicKey) {
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            throw new BizException("PAY_SIGN_MISSING", "回调缺少签名头");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BizException("PAY_SIGN_INVALID", "回调时间戳格式非法");
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - ts);
        if (skew > toleranceSeconds) {
            // 不校验窗口 = 允许重放旧报文；这里宁可拒掉一条真实回调（微信会重试）
            throw new BizException("PAY_SIGN_EXPIRED",
                    "回调时间戳超出容忍窗口（偏差 " + skew + "s > " + toleranceSeconds + "s）");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new BizException("PAY_SIGN_INVALID", "回调验签失败");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("PAY_SIGN_INVALID", "回调验签异常：" + e.getMessage());
        }
    }

    /**
     * 解密 {@code resource} 节点（也用于解密平台证书）。
     *
     * @param associatedData 微信下发的 associated_data，原样传入
     */
    public String decrypt(String associatedData, String nonce, String ciphertext) {
        if (isBlank(nonce) || isBlank(ciphertext)) {
            throw new BizException("PAY_DECRYPT_FAILED", "回调密文缺失");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(apiV3Key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("PAY_DECRYPT_FAILED", "回调解密失败：" + e.getMessage());
        }
    }

    public Instant now() {
        return clock.instant();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
