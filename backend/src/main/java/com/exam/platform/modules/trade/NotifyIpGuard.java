package com.exam.platform.modules.trade;

import com.exam.platform.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 支付回调的来源 IP 白名单。
 *
 * <p>验签能防「伪造内容」，防不了「重放」与「用合法签名试探」——为这类请求先加一道 IP 门槛，
 * 把大部分噪声挡在验签之前。默认<b>关闭</b>：配置为空时不校验，这样本地开发、
 * 内网联调、以及尚未拿到出口 IP 的首次上线都不受影响。</p>
 *
 * <p>开启方式：配置 {@code payment.wechat.notify-ips}（逗号分隔，支持精确 IP）。
 * 一旦配置了，白名单之外的请求会被直接拒绝——这比「验签失败」更早、更便宜。</p>
 *
 * <p>取 IP 的规则：优先 {@code X-Forwarded-For} 最左侧，因为生产环境前面有反向代理，
 * {@code remoteAddr} 会变成代理地址。注意 XFF 可被客户端伪造，所以只有在代理层
 * <b>覆盖</b>（而不是追加）该头时才真正可信；部署文档里需要写明这一点。</p>
 */
@Component
public class NotifyIpGuard {

    private final List<String> allowedIps;

    public NotifyIpGuard(@Value("${payment.wechat.notify-ips:}") String configured) {
        this.allowedIps = configured == null || configured.isBlank()
                ? List.of()
                : Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /** 未配置白名单时放行；配置了则来源 IP 必须在其中。 */
    public void check(HttpServletRequest request) {
        if (allowedIps.isEmpty()) {
            return;
        }
        String ip = clientIp(request);
        if (!allowedIps.contains(ip)) {
            throw BizException.forbidden("回调来源 IP 不在白名单：" + ip);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 只取最左侧：右侧是各级代理追加的地址，最左侧才代表发起方
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
