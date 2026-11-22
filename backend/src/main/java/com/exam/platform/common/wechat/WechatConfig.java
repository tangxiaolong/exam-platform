package com.exam.platform.common.wechat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

/**
 * 微信接入装配。
 *
 * <p>{@code app.wechat.mock=true}（默认）时全部走本地实现，生产必须显式置为 false。
 * 置为 false 后如果关键参数缺失，<b>直接让应用启动失败</b>——而不是启动成功、
 * 等第一笔真实支付时才在回调验签上炸掉。改错配置的成本远低于事故成本。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WechatPayProperties.class)
public class WechatConfig {

    // ------------------------------------------------------------ 登录

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "true", matchIfMissing = true)
    public WechatAuthClient mockWechatAuthClient() {
        log.info("微信登录使用 mock 实现（app.wechat.mock=true）");
        return new WechatAuthClients.Mock();
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatAuthClient httpWechatAuthClient(
            @Value("${app.wechat.app-id:}") String appId,
            @Value("${app.wechat.app-secret:}") String appSecret) {
        log.info("微信登录使用真实 jscode2session，appId={}", appId);
        return new WechatAuthClients.Http(appId, appSecret);
    }

    // ------------------------------------------------------------ 支付

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "true", matchIfMissing = true)
    public WechatPayGateway mockWechatPayGateway() {
        log.info("微信支付使用 mock 网关（app.wechat.mock=true），dev 回调接口开放");
        return new MockWechatPayGateway();
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "true", matchIfMissing = true)
    public WechatPayNotifyHandler disabledWechatPayNotifyHandler() {
        return new WechatPayNotifyHandler.Disabled();
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPaySigner wechatPaySigner(WechatPayProperties properties) {
        validate(properties);
        PrivateKey privateKey = WechatPaySigner.loadPrivateKey(resolvePrivateKey(properties));
        return new WechatPaySigner(properties.getMchId(), properties.getMerchantSerialNumber(), privateKey);
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPayVerifier wechatPayVerifier(WechatPayProperties properties) {
        return new WechatPayVerifier(properties.getApiV3Key(), properties.getTimestampToleranceSeconds());
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPayHttpClient wechatPayHttpClient(WechatPayProperties properties,
                                                   WechatPaySigner signer,
                                                   WechatPayVerifier verifier) {
        return new WechatPayHttpClient(signer, verifier, properties.getApiBase());
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPayCertificateProvider wechatPayCertificateProvider(WechatPayProperties properties,
                                                                     WechatPayVerifier verifier,
                                                                     WechatPayHttpClient client) {
        WechatPayCertificateProvider provider = new WechatPayCertificateProvider(
                verifier, client::get, properties.getCertificateRefreshIntervalSeconds());
        // 双向依赖在这里收口：client 验响应要证书，provider 拉证书要 client
        client.setCertificateProvider(provider);
        try {
            provider.refresh();
        } catch (Exception e) {
            // 微信侧抖动不该让整个平台起不来；首次回调会再触发一次刷新，这里把风险喊出来
            log.error("启动时拉取微信平台证书失败，将在首次回调时重试：{}", e.getMessage());
        }
        return provider;
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPayGateway wechatPayGateway(WechatPayProperties properties,
                                             WechatPayHttpClient client,
                                             WechatPaySigner signer) {
        validate(properties);
        log.info("微信支付使用真实 v3 网关，mchId={} notifyUrl={}", properties.getMchId(), properties.getNotifyUrl());
        return new HttpWechatPayGateway(client, signer, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.wechat.mock", havingValue = "false")
    public WechatPayNotifyHandler v3WechatPayNotifyHandler(WechatPayProperties properties,
                                                           WechatPayVerifier verifier,
                                                           WechatPayCertificateProvider certificateProvider) {
        return new V3WechatPayNotifyHandler(verifier, certificateProvider::publicKey,
                properties.getAppId(), properties.getMchId());
    }

    private void validate(WechatPayProperties p) {
        StringBuilder missing = new StringBuilder();
        if (blank(p.getMchId())) {
            missing.append(" mch-id");
        }
        if (blank(p.getAppId())) {
            missing.append(" app-id");
        }
        if (blank(p.getApiV3Key())) {
            missing.append(" api-v3-key");
        }
        if (blank(p.getMerchantSerialNumber())) {
            missing.append(" merchant-serial-number");
        }
        if (blank(p.getPrivateKey()) && blank(p.getPrivateKeyPath())) {
            missing.append(" private-key/private-key-path");
        }
        if (blank(p.getNotifyUrl())) {
            missing.append(" notify-url");
        }
        if (missing.length() > 0) {
            throw new IllegalStateException("app.wechat.mock=false 但缺少支付配置：" + missing);
        }
        if (!p.getNotifyUrl().startsWith("https://")) {
            // 微信只回调 https；写成 http 的话本地一切正常，线上永远收不到通知
            throw new IllegalStateException("app.wechat.pay.notify-url 必须是 https 公网地址：" + p.getNotifyUrl());
        }
    }

    private String resolvePrivateKey(WechatPayProperties p) {
        if (!blank(p.getPrivateKey())) {
            return p.getPrivateKey();
        }
        try {
            return Files.readString(Path.of(p.getPrivateKeyPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取商户私钥失败：" + p.getPrivateKeyPath(), e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
