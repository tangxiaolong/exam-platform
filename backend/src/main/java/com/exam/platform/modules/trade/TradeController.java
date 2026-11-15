package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import com.exam.platform.modules.entitlement.entity.EntitlementLedger;
import com.exam.platform.modules.trade.dto.CreateOrderRequest;
import com.exam.platform.modules.trade.dto.NotifyResult;
import com.exam.platform.modules.trade.dto.PaymentCreateResponse;
import com.exam.platform.modules.trade.dto.RefundApplyRequest;
import com.exam.platform.modules.trade.dto.WechatNotifyRequest;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商城 / 订单 / 支付 / 权益（考生端）。 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final RefundService refundService;
    private final EntitlementService entitlementService;

    @GetMapping("/products")
    public Result<PageResult<MallProduct>> products(@RequestParam(required = false) String keyword,
                                                    PageQuery pageQuery) {
        return Result.ok(tradeService.pageProducts(keyword, true, pageQuery));
    }

    @GetMapping("/products/{id}")
    public Result<MallProduct> product(@PathVariable Long id) {
        return Result.ok(tradeService.getProduct(id));
    }

    @PostMapping("/orders")
    public Result<TradeOrder> createOrder(@RequestBody CreateOrderRequest request) {
        return Result.ok(tradeService.createOrder(UserContext.userId(), request));
    }

    @GetMapping("/orders")
    public Result<PageResult<TradeOrder>> myOrders(@RequestParam(required = false) String status,
                                                   PageQuery pageQuery) {
        return Result.ok(tradeService.pageMyOrders(UserContext.userId(), status, pageQuery));
    }

    @GetMapping("/orders/{id}")
    public Result<TradeOrder> order(@PathVariable Long id) {
        return Result.ok(tradeService.getOwnedOrder(id, UserContext.userId()));
    }

    @PostMapping("/orders/{id}/payments")
    public Result<PaymentCreateResponse> pay(@PathVariable Long id) {
        return Result.ok(tradeService.createPayment(id, UserContext.userId()));
    }

    @GetMapping("/entitlements")
    public Result<List<EntitlementAccount>> entitlements() {
        return Result.ok(entitlementService.listByUser(UserContext.userId()));
    }

    @GetMapping("/entitlements/ledger")
    public Result<List<EntitlementLedger>> ledger() {
        return Result.ok(entitlementService.ledgerByUser(UserContext.userId()));
    }

    /** 权益核销（如：用题库包次数进入一次付费练习）。requestId 相同则幂等。 */
    @PostMapping("/entitlements/{id}/consume")
    public Result<EntitlementAccount> consume(@PathVariable Long id,
                                              @RequestParam(defaultValue = "1") int amount,
                                              @RequestParam(required = false) String requestId) {
        return Result.ok(entitlementService.consume(UserContext.userId(), id, amount, requestId));
    }

    /** 申请退款（首期为整单全额）。 */
    @PostMapping("/orders/{id}/refunds")
    public Result<TradeRefund> applyRefund(@PathVariable Long id,
                                           @RequestBody(required = false) RefundApplyRequest request) {
        return Result.ok(refundService.apply(UserContext.userId(), id,
                request == null ? null : request.reason()));
    }

    @GetMapping("/refunds")
    public Result<PageResult<TradeRefund>> myRefunds(PageQuery pageQuery) {
        return Result.ok(refundService.pageMy(UserContext.userId(), pageQuery));
    }

    /** 微信支付回调（白名单接口，无登录态）。 */
    @PostMapping("/payments/wechat/notify")
    public Result<NotifyResult> wechatNotify(@RequestBody String rawBody) {
        WechatNotifyRequest request = Json.read(rawBody, WechatNotifyRequest.class);
        if (request == null) {
            throw BizException.badRequest("回调内容为空");
        }
        NotifyResult result = tradeService.handleNotify(request, rawBody);
        return result.success() ? Result.ok(result) : Result.fail(result.code(), result.message());
    }
}
