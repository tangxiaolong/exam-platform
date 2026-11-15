package com.exam.platform.modules.trade;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.trade.dto.NotifyResult;
import com.exam.platform.modules.trade.dto.WechatNotifyRequest;
import com.exam.platform.modules.trade.entity.TradePayment;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅本地联调使用：模拟微信支付成功回调。
 *
 * <p>生产环境必须关闭 {@code app.wechat.mock}，该接口随即失效；
 * 它只走与服务端完全相同的回调处理链路（验签、金额核对、幂等发放权益）。</p>
 */
@RestController
@RequestMapping("/v1/dev")
@RequiredArgsConstructor
public class DevPaymentController {

    private final TradeService tradeService;

    @PostMapping("/payments/{payNo}/mock-notify")
    public Result<NotifyResult> mockNotify(@PathVariable String payNo) {
        if (!tradeService.isWechatMock()) {
            throw BizException.forbidden("模拟支付回调接口已关闭");
        }
        TradePayment payment = tradeService.getPaymentByPayNo(payNo);
        if (payment == null) {
            throw BizException.notFound("支付单");
        }
        WechatNotifyRequest request = new WechatNotifyRequest(payment.getPayNo(),
                "MOCK-TXN-" + payment.getPayNo(),
                payment.getAmountFen(), "CNY", "SUCCESS",
                tradeService.sign(payment.getPayNo(), payment.getAmountFen()));
        return Result.ok(tradeService.handleNotify(request, Json.write(request)));
    }
}
