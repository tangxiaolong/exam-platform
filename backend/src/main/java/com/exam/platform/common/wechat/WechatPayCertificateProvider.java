package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 微信支付平台证书管理（含轮换）。
 *
 * <p>平台证书会定期轮换，且<b>新旧证书并存一段时间</b>。回调头里的 {@code Wechatpay-Serial}
 * 指向「签名时用的那一张」，所以绝不能只缓存一张证书：一旦换了证书，
 * 缓存里没有对应 serial 就会把所有回调判成验签失败，而支付成功通知会一直重试、订单永远不到账。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>正常路径按 serial 命中缓存；</li>
 *   <li>命中不到时<b>立即拉取一次</b>（轮换的兜底），仍没有才判定为未知证书；</li>
 *   <li>定时刷新（默认 12h）保证已知证书不过期，刷新失败保留旧证书——</li>
 *   <li>拉取期间加锁，避免回调风暴把证书接口打爆。</li>
 * </ul>
 */
@Slf4j
public class WechatPayCertificateProvider {

    private static final String CERTIFICATES_PATH = "/v3/certificates";

    private final WechatPayVerifier verifier;
    /** 传入 path，返回响应 body；由 HTTP 客户端实现（自带商户签名）。 */
    private final Function<String, String> rawGet;
    private final long refreshIntervalSeconds;

    private final Map<String, X509Certificate> certificates = new ConcurrentHashMap<>();
    private final Object refreshLock = new Object();
    private volatile long lastRefreshAt;

    public WechatPayCertificateProvider(WechatPayVerifier verifier,
                                        Function<String, String> rawGet,
                                        long refreshIntervalSeconds) {
        this.verifier = verifier;
        this.rawGet = rawGet;
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    /** 按回调头里的 serial 取证书；未知 serial 会触发一次刷新（应对轮换）。 */
    public X509Certificate certificate(String serial) {
        if (serial == null || serial.isBlank()) {
            throw new BizException("PAY_CERT_MISSING", "回调缺少 Wechatpay-Serial 头");
        }
        X509Certificate cached = certificates.get(serial);
        if (cached != null) {
            return cached;
        }
        // 没命中就刷一次：证书轮换窗口内的新回调全靠这条路径才验得过
        refresh();
        X509Certificate fetched = certificates.get(serial);
        if (fetched == null) {
            throw new BizException("PAY_CERT_UNKNOWN",
                    "未知的平台证书序列号：" + serial + "，已刷新 " + certificates.size() + " 张证书仍未找到");
        }
        return fetched;
    }

    /** 距上次成功刷新超过间隔才真正拉取，供定时任务调用。 */
    public void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAt >= refreshIntervalSeconds * 1000L) {
            refresh();
        }
    }

    /** 强制拉取平台证书。失败时保留旧证书并抛异常给调用方决定是否降级。 */
    public void refresh() {
        synchronized (refreshLock) {
            String body;
            try {
                body = rawGet.apply(CERTIFICATES_PATH);
            } catch (Exception e) {
                // 拉取失败不清缓存：旧证书在过期前仍然有效，清掉等于把所有回调判失败
                log.warn("拉取微信平台证书失败，继续使用已有 {} 张证书", certificates.size(), e);
                throw new BizException("PAY_CERT_REFRESH_FAILED", "拉取平台证书失败：" + e.getMessage());
            }
            CertificatesResponse response = Json.read(body, CertificatesResponse.class);
            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("微信平台证书响应为空，保留已有 {} 张证书", certificates.size());
                return;
            }
            int loaded = 0;
            for (CertificateItem item : response.data()) {
                try {
                    String pem = verifier.decrypt(item.encryptCertificate().associatedData(),
                            item.encryptCertificate().nonce(), item.encryptCertificate().ciphertext());
                    X509Certificate cert = parse(pem);
                    certificates.put(item.serialNo(), cert);
                    loaded++;
                } catch (Exception e) {
                    // 单张证书解不开不影响其他张；但必须留日志，否则轮换失败会表现为「全部验签失败」
                    log.warn("解析平台证书失败 serial={}：{}", item.serialNo(), e.getMessage());
                }
            }
            lastRefreshAt = System.currentTimeMillis();
            log.info("微信平台证书已刷新，解析成功 {} 张，缓存共 {} 张", loaded, certificates.size());
        }
    }

    public int size() {
        return certificates.size();
    }

    /** 供回调验签使用的窄接口：调用方只需要公钥，不必接触证书对象。 */
    public PublicKey publicKey(String serial) {
        return certificate(serial).getPublicKey();
    }

    private X509Certificate parse(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X509");
        return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CertificatesResponse(List<CertificateItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CertificateItem(@JsonProperty("serial_no") String serialNo,
                           @JsonProperty("encrypt_certificate") EncryptCertificate encryptCertificate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EncryptCertificate(@JsonProperty("nonce") String nonce,
                              @JsonProperty("associated_data") String associatedData,
                              @JsonProperty("ciphertext") String ciphertext) {
    }
}
