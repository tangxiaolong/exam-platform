package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 微信支付 v3 的 HTTP 通道：负责「签名发请求 + 校验响应签名」，
 * 至于调哪个接口由 {@link HttpWechatPayGateway} 与证书提供者各自表达。
 *
 * <p>为什么不直接在每个调用点拼 Authorization：签名串对换行、URL（含 query）
 * 极其敏感，集中在一处才有一致的失败面——签名错了永远是 401，比偶发 500 好查。</p>
 */
@Slf4j
public class WechatPayHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final WechatPaySigner signer;
    private final WechatPayVerifier verifier;
    private final String apiBase;

    /** 构造后再注入，避免与证书提供者互相依赖。 */
    private WechatPayCertificateProvider certificateProvider;

    public WechatPayHttpClient(WechatPaySigner signer, WechatPayVerifier verifier, String apiBase) {
        this.signer = signer;
        this.verifier = verifier;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .sslContext(defaultSslContext())
                .build();
    }

    public void setCertificateProvider(WechatPayCertificateProvider certificateProvider) {
        this.certificateProvider = certificateProvider;
    }

    /** GET；{@code pathWithQuery} 必须以 / 开头且 query 顺序与签名时一致。 */
    public String get(String pathWithQuery) {
        return send("GET", pathWithQuery, "");
    }

    public String post(String path, String body) {
        return send("POST", path, body == null ? "" : body);
    }

    private String send(String method, String pathWithQuery, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + pathWithQuery))
                .timeout(TIMEOUT)
                .header("Authorization", signer.authorization(method, pathWithQuery, body))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "exam-platform/1.0")
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            verifyResponse(response, responseBody);
            if (response.statusCode() / 100 != 2) {
                throw new BizException("PAY_GATEWAY_ERROR",
                        "微信支付接口返回 " + response.statusCode() + "：" + responseBody);
            }
            return responseBody;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new BizException("PAY_GATEWAY_UNREACHABLE", "调用微信支付接口失败：" + e.getMessage());
        }
    }

    /**
     * 响应同样要验签，否则无法区分「微信说支付成功」和「中间人伪造的成功响应」。
     * 没有签名头时不静默放行，而是显式告警——正常运行的微信接口一定带签名。
     */
    private void verifyResponse(HttpResponse<String> response, String body) {
        Optional<String> serial = response.headers().firstValue("Wechatpay-Serial");
        Optional<String> signature = response.headers().firstValue("Wechatpay-Signature");
        Optional<String> timestamp = response.headers().firstValue("Wechatpay-Timestamp");
        Optional<String> nonce = response.headers().firstValue("Wechatpay-Nonce");
        if (serial.isEmpty() || signature.isEmpty() || timestamp.isEmpty() || nonce.isEmpty()) {
            log.warn("微信支付响应缺少签名头，跳过响应验签：status={} path={}",
                    response.statusCode(), response.uri().getPath());
            return;
        }
        if (certificateProvider == null) {
            log.warn("平台证书提供者未装配，跳过响应验签");
            return;
        }
        verifier.verify(timestamp.get(), nonce.get(), signature.get(), body,
                certificateProvider.certificate(serial.get()).getPublicKey());
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 上下文失败", e);
        }
    }
}
