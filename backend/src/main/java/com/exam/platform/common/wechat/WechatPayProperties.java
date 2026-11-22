package com.exam.platform.common.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付 v3 与小程序登录的接入参数。
 *
 * <p>所有敏感项（商户私钥、APIv3 密钥）都从环境变量注入，不写进仓库：
 * <pre>
 * APP_WECHAT_MOCK=false
 * APP_WECHAT_APP_ID=wx...
 * APP_WECHAT_APP_SECRET=...
 * APP_WECHAT_PAY_MCH_ID=1900000000
 * APP_WECHAT_PAY_API_V3_KEY=32字节
 * APP_WECHAT_PAY_MERCHANT_SERIAL_NUMBER=证书序列号
 * APP_WECHAT_PAY_PRIVATE_KEY_PATH=/etc/exam-platform/apiclient_key.pem
 * APP_WECHAT_PAY_NOTIFY_URL=https://exam.example.com/api/v1/payments/wechat/notify
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "app.wechat.pay")
public class WechatPayProperties {

    /** 商户号。 */
    private String mchId;

    /** 小程序 appid（v3 下单必填，且必须与 mchId 绑定）。 */
    private String appId;

    /** APIv3 密钥（32 字节）：既用于回调解密，也用于平台证书解密。 */
    private String apiV3Key;

    /** 商户 API 证书序列号（不是平台证书序列号）。 */
    private String merchantSerialNumber;

    /** 商户私钥，PKCS#8 PEM 内容。与 privateKeyPath 二选一，优先内容。 */
    private String privateKey;

    /** 商户私钥文件路径（容器里通常用挂载的密钥文件）。 */
    private String privateKeyPath;

    /** 支付结果回调地址，必须是公网可达的 https。 */
    private String notifyUrl;

    /** API 域名，沙箱/代理环境可覆盖。 */
    private String apiBase = "https://api.mch.weixin.qq.com";

    /**
     * 回调/应答的时间戳容忍窗口（秒）。超出即拒绝：
     * 微信的签名里带 timestamp，没有窗口校验等于允许无限期重放旧报文。
     */
    private long timestampToleranceSeconds = 300L;

    /** 平台证书刷新间隔（秒），默认 12 小时。证书轮换期间靠「未知 serial 主动刷新」兜底。 */
    private long certificateRefreshIntervalSeconds = 43_200L;

    /**
     * 支付单「创建后多久仍未支付」视为需要主动查单/关单（分钟）。
     * 必须小于订单有效期（30 分钟），否则会出现「订单已关但支付单还在等」的空窗。
     */
    private long unpaidTimeoutMinutes = 15L;

    /** 补偿任务 cron：默认每 10 分钟一次。 */
    private String compensationCron = "0 */10 * * * *";

    /** 单轮补偿最多处理多少笔，避免一次扫全库把支付网关打爆。 */
    private int compensationBatchSize = 100;

    /** 补偿任务的预期周期（秒），必须与 compensationCron 对应，供任务健康告警判断 staleness。 */
    private long compensationExpectedIntervalSeconds = 600L;

    /** 是否启用补偿任务（关单 + 主动查单）。 */
    private boolean compensationEnabled = true;
}
