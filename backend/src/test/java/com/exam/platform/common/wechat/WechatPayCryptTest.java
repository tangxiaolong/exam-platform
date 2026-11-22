package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付 v3 的验签 / 解密 / 回调解析单测。
 *
 * <p>这一层全是纯计算，不连网也能验证：自建 RSA 密钥对当作「平台证书」，
 * 自己按微信的规则签名与加密，然后确认实现能验过、能拒掉篡改。
 * 真实的商户号与证书是发布前的最后一米，前面的逻辑不该依赖它才能测。</p>
 */
class WechatPayCryptTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static KeyPair keyPair;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    // ---------------------------------------------------------------- 验签

    @Test
    void verify_acceptsSignatureProducedWithSameRules() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String body = "{\"id\":\"notify-1\"}";

        assertThatCode(() -> verifier.verify(timestamp, nonce, sign(timestamp, nonce, body), body,
                keyPair.getPublic())).doesNotThrowAnyException();
    }

    @Test
    void verify_rejectsTamperedBody() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String body = "{\"id\":\"notify-1\"}";
        String signature = sign(timestamp, nonce, body);

        // 签名只覆盖原文：改一个字节就必须验不过，否则「验签」等于没有
        assertThatThrownBy(() -> verifier.verify(timestamp, nonce, signature,
                "{\"id\":\"notify-2\"}", keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_SIGN_INVALID");
    }

    @Test
    void verify_rejectsReplayedOldTimestamp() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        String timestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        String nonce = "nonce-1";
        String body = "{\"id\":\"notify-1\"}";

        // 没有时间窗校验 = 一份有效报文可以被无限期重放
        assertThatThrownBy(() -> verifier.verify(timestamp, nonce, sign(timestamp, nonce, body), body,
                keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_SIGN_EXPIRED");
    }

    @Test
    void verify_rejectsMissingHeaders() {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        assertThatThrownBy(() -> verifier.verify(null, "n", "s", "{}", keyPair.getPublic()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_SIGN_MISSING");
    }

    @Test
    void verifier_rejectsWrongApiV3KeyLength() {
        assertThatThrownBy(() -> new WechatPayVerifier("short-key", 300))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_APIV3_KEY_INVALID");
    }

    // ---------------------------------------------------------------- 解密

    @Test
    void decrypt_roundTripsAeadPayload() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        String plain = "{\"out_trade_no\":\"PAY-1\"}";
        String nonce = "abcdefghijkl";
        String ciphertext = encrypt(plain, "transaction", nonce);

        assertThat(verifier.decrypt("transaction", nonce, ciphertext)).isEqualTo(plain);
    }

    @Test
    void decrypt_failsWhenAssociatedDataTampered() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        String nonce = "abcdefghijkl";
        String ciphertext = encrypt("{\"a\":1}", "transaction", nonce);

        // AAD 参与 GCM 认证：改 AAD 必须解密失败，否则等于没有完整性保护
        assertThatThrownBy(() -> verifier.decrypt("certificate", nonce, ciphertext))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_DECRYPT_FAILED");
    }

    // ---------------------------------------------------------------- v3 回调全链路

    @Test
    void notifyHandler_parsesSignedEncryptedCallback() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        V3WechatPayNotifyHandler handler = new V3WechatPayNotifyHandler(
                verifier, serial -> keyPair.getPublic(), "wx-app", "1900000000");

        String plain = Json.write(Map.of(
                "out_trade_no", "PAY-1",
                "transaction_id", "TXN-1",
                "trade_state", "SUCCESS",
                "appid", "wx-app",
                "mchid", "1900000000",
                "amount", Map.of("total", 9900, "currency", "CNY")));
        String resourceNonce = "resourceNonce";
        String body = envelopeJson("TRANSACTION.SUCCESS", encrypt(plain, "transaction", resourceNonce), resourceNonce);

        WechatPayNotifyHandler.Notify notify = handler.parse(signedHeaders(body), body);

        assertThat(notify.payNo()).isEqualTo("PAY-1");
        assertThat(notify.transactionId()).isEqualTo("TXN-1");
        assertThat(notify.tradeState()).isEqualTo("SUCCESS");
        assertThat(notify.amountFen()).isEqualTo(9900L);
        assertThat(notify.currency()).isEqualTo("CNY");
    }

    @Test
    void notifyHandler_rejectsForeignMerchant() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        V3WechatPayNotifyHandler handler = new V3WechatPayNotifyHandler(
                verifier, serial -> keyPair.getPublic(), "wx-app", "1900000000");

        String plain = Json.write(Map.of(
                "out_trade_no", "PAY-1", "transaction_id", "TXN-1", "trade_state", "SUCCESS",
                "appid", "wx-app", "mchid", "1900000001",
                "amount", Map.of("total", 9900, "currency", "CNY")));
        String resourceNonce = "resourceNonce";
        String body = envelopeJson("TRANSACTION.SUCCESS", encrypt(plain, "transaction", resourceNonce), resourceNonce);

        // 验签只证明「来自微信」，不证明「是我们的单」：同平台多商户共用回调地址时必须挡住
        assertThatThrownBy(() -> handler.parse(signedHeaders(body), body))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_NOTIFY_FOREIGN");
    }

    @Test
    void notifyHandler_rejectsUnsignedCallback() throws Exception {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        V3WechatPayNotifyHandler handler = new V3WechatPayNotifyHandler(
                verifier, serial -> keyPair.getPublic(), "wx-app", "1900000000");
        String body = envelopeJson("TRANSACTION.SUCCESS", "not-base64-cipher", "n");

        assertThatThrownBy(() -> handler.parse(Map.of(), body))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_SIGN_MISSING");
    }

    // ---------------------------------------------------------------- 平台证书轮换

    @Test
    void certificateProvider_reportsUnknownSerialInsteadOfSilentlyPassing() {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        // 刷新后仍没有这张证书：必须显式失败，静默跳过验签等于放行伪造回调
        WechatPayCertificateProvider provider = new WechatPayCertificateProvider(
                verifier, path -> "{\"data\":[]}", 60);

        assertThatThrownBy(() -> provider.certificate("serial-unknown"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_CERT_UNKNOWN");
    }

    @Test
    void certificateProvider_surfacesRefreshFailure() {
        WechatPayVerifier verifier = new WechatPayVerifier(API_V3_KEY, 300);
        WechatPayCertificateProvider provider = new WechatPayCertificateProvider(verifier, path -> {
            throw new IllegalStateException("网络不可用");
        }, 60);

        assertThatThrownBy(() -> provider.certificate("serial-1"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "PAY_CERT_REFRESH_FAILED");
        assertThat(provider.size()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private static String sign(String timestamp, String nonce, String body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static Map<String, String> signedHeaders(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "headerNonce";
        return Map.of(
                "Wechatpay-Timestamp", timestamp,
                "Wechatpay-Nonce", nonce,
                "Wechatpay-Signature", sign(timestamp, nonce, body),
                "Wechatpay-Serial", "serial-1");
    }

    private static String encrypt(String plain, String associatedData, String nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (associatedData != null && !associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String envelopeJson(String eventType, String ciphertext, String resourceNonce) {
        return Json.write(Map.of(
                "id", "notify-1",
                "event_type", eventType,
                "resource_type", "encrypt-resource",
                "resource", Map.of(
                        "algorithm", "AEAD_AES_256_GCM",
                        "ciphertext", ciphertext,
                        "nonce", resourceNonce,
                        "associated_data", "transaction")));
    }
}
