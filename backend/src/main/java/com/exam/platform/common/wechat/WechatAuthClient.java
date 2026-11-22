package com.exam.platform.common.wechat;

/**
 * 微信小程序登录凭证换取 openid 的抽象。
 *
 * <p>真实环境走 {@code jscode2session}；本地联调走 mock（由 code 派生稳定 openid），
 * 两者对上层暴露同一份契约，AuthService 不需要知道自己连的是不是微信。</p>
 */
public interface WechatAuthClient {

    /** 是否 mock 实现（用于启动自检与日志）。 */
    boolean mock();

    /**
     * code 换 openid。
     *
     * @throws com.exam.platform.common.exception.BizException code/网络异常统一转成语义化错误码
     */
    String exchangeCode(String code);
}
