package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** 微信登录客户端的两个实现。 */
public final class WechatAuthClients {

    private WechatAuthClients() {
    }

    /**
     * 本地联调实现：由 code 派生稳定 openid。
     *
     * <p>同一 code 永远得到同一 openid，这样「同一用户多次登录拿到同一账号」在本地也成立；
     * 用随机值会让每次调试都新建用户，掩盖掉唯一索引和账号合并的问题。</p>
     */
    public static class Mock implements WechatAuthClient {

        @Override
        public boolean mock() {
            return true;
        }

        @Override
        public String exchangeCode(String code) {
            if (code == null || code.isBlank()) {
                throw new BizException("AUTH_WECHAT_CODE_EMPTY", "登录凭证不能为空");
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
                return "mock-openid-" + HexFormat.of().formatHex(hash).substring(0, 20);
            } catch (Exception e) {
                throw new IllegalStateException("openid 派生失败", e);
            }
        }
    }

    /**
     * 真实实现：调用 {@code sns/jscode2session}。
     *
     * <p>微信这个接口有一件事必须记住：<b>它失败时 HTTP 状态码通常还是 200</b>，
     * 错误信息在 body 的 errcode 里。只判断 HTTP 状态会把「code 无效」当成登录成功。</p>
     */
    @Slf4j
    public static class Http implements WechatAuthClient {

        private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";

        private final String appId;
        private final String appSecret;
        private final HttpClient httpClient;

        public Http(String appId, String appSecret) {
            if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
                throw new IllegalStateException("app.wechat.mock=false 时必须配置 app.wechat.app-id 与 app.wechat.app-secret");
            }
            this.appId = appId;
            this.appSecret = appSecret;
            this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }

        @Override
        public boolean mock() {
            return false;
        }

        @Override
        public String exchangeCode(String code) {
            if (code == null || code.isBlank()) {
                throw new BizException("AUTH_WECHAT_CODE_EMPTY", "登录凭证不能为空");
            }
            String url = ENDPOINT
                    + "?appid=" + encode(appId)
                    + "&secret=" + encode(appSecret)
                    + "&js_code=" + encode(code)
                    + "&grant_type=authorization_code";
            String body;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                body = response.body();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new BizException("AUTH_WECHAT_UNREACHABLE", "调用微信登录接口失败，请稍后重试");
            }
            Jscode2SessionResponse parsed = Json.read(body, Jscode2SessionResponse.class);
            if (parsed == null) {
                throw new BizException("AUTH_WECHAT_FAILED", "微信登录接口返回为空");
            }
            if (parsed.errCode() != null && parsed.errCode() != 0) {
                throw mapError(parsed.errCode(), parsed.errMsg());
            }
            if (parsed.openId() == null || parsed.openId().isBlank()) {
                throw new BizException("AUTH_WECHAT_FAILED", "微信登录未返回 openid");
            }
            return parsed.openId();
        }

        private BizException mapError(int errCode, String errMsg) {
            String detail = errMsg == null ? "" : errMsg;
            log.warn("jscode2session 失败 errcode={} errmsg={}", errCode, detail);
            return switch (errCode) {
                case 40029 -> new BizException("AUTH_WECHAT_CODE_INVALID", "登录凭证已失效，请重新进入小程序");
                case 40163 -> new BizException("AUTH_WECHAT_CODE_USED", "登录凭证已被使用，请重新进入小程序");
                case 45011 -> new BizException("AUTH_WECHAT_RATE_LIMITED", "登录过于频繁，请稍后重试");
                case 40013, 40125 -> new BizException("AUTH_WECHAT_CONFIG_INVALID", "小程序 appid/secret 配置不正确");
                default -> new BizException("AUTH_WECHAT_FAILED", "微信登录失败(" + errCode + ")：" + detail);
            };
        }

        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    record Jscode2SessionResponse(@com.fasterxml.jackson.annotation.JsonProperty("openid") String openId,
                                  @com.fasterxml.jackson.annotation.JsonProperty("session_key") String sessionKey,
                                  @com.fasterxml.jackson.annotation.JsonProperty("unionid") String unionId,
                                  @com.fasterxml.jackson.annotation.JsonProperty("errcode") Integer errCode,
                                  @com.fasterxml.jackson.annotation.JsonProperty("errmsg") String errMsg) {
    }
}
