package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Json;
import com.exam.platform.common.wechat.WechatPayNotifyHandler;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import com.exam.platform.modules.entitlement.entity.EntitlementLedger;
import com.exam.platform.modules.trade.dto.CreateOrderRequest;
import com.exam.platform.modules.trade.dto.NotifyResult;
import com.exam.platform.modules.trade.dto.PaymentCreateResponse;
import com.exam.platform.modules.trade.dto.RefundApplyRequest;
import com.exam.platform.modules.trade.dto.WechatNotifyRequest;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.MallSku;
import com.exam.platform.modules.trade.entity.MarketingCoupon;
import com.exam.platform.modules.trade.entity.MarketingCouponGrant;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeOrderItem;
import com.exam.platform.modules.trade.entity.TradeRefund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 商城 / 订单 / 支付 / 权益（考生端）。 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final RefundService refundService;
    private final EntitlementService entitlementService;
    private final CouponService couponService;
    private final WechatPayNotifyHandler wechatPayNotifyHandler;
    private final NotifyIpGuard notifyIpGuard;

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
    /** 申请退款。不传 orderItemId 为整单退款，传则为按明细的部分退款。 */
    @PostMapping("/orders/{id}/refunds")
    public Result<TradeRefund> applyRefund(@PathVariable Long id,
                                           @RequestBody(required = false) RefundApplyRequest request) {
        return Result.ok(refundService.apply(UserContext.userId(), id, request));
    }

    // ---------------------------------------------------------------- SKU / 明细

    /** 商品的在售规格列表（前端选规格用）。 */
    @GetMapping("/products/{id}/skus")
    public Result<List<MallSku>> productSkus(@PathVariable Long id) {
        return Result.ok(tradeService.listSkus(id, true));
    }

    /** 订单明细：退款按行退，用户要能看到自己买的是哪几行。 */
    @GetMapping("/orders/{id}/items")
    public Result<List<TradeOrderItem>> orderItems(@PathVariable Long id) {
        tradeService.getOwnedOrder(id, UserContext.userId());
        return Result.ok(tradeService.listOrderItems(id));
    }

    // ---------------------------------------------------------------- 优惠券

    /** 可领的券（已领完 / 超人均的不返回，避免用户点了才报错）。 */
    @GetMapping("/coupons/claimable")
    public Result<List<MarketingCoupon>> claimableCoupons() {
        return Result.ok(couponService.listClaimable(UserContext.userId()));
    }

    @PostMapping("/coupons/{code}/claim")
    public Result<MarketingCouponGrant> claimCoupon(@PathVariable String code) {
        return Result.ok(couponService.claim(UserContext.userId(), code));
    }

    /** 我的券。status 缺省不筛选，传 UNUSED / LOCKED / USED 可分别查看。 */
    @GetMapping("/coupons/mine")
    public Result<List<CouponGrantView>> myCoupons(@RequestParam(required = false) String status) {
        return Result.ok(couponService.listMine(UserContext.userId(), status));
    }

    @GetMapping("/refunds")
    public Result<PageResult<TradeRefund>> myRefunds(PageQuery pageQuery) {
        return Result.ok(refundService.pageMy(UserContext.userId(), pageQuery));
    }

    /**
     * 微信支付回调（白名单接口，无登录态）。
     *
     * <p>应答格式按渠道区分，这一点做错会导致「支付成功但订单一直不到账」：</p>
     * <ul>
     *   <li>v3 只认 <b>HTTP 200 + {"code":"SUCCESS"}</b>，返回我们的 {@code Result} 包装
     *       会被判为处理失败并持续重试；</li>
     *   <li>验签/解密失败应返回非 2xx + {"code":"FAIL"}，同样是为了让微信重试而不是丢弃。</li>
     * </ul>
     */
    @PostMapping("/payments/wechat/notify")
    public ResponseEntity<?> wechatNotify(@RequestBody String rawBody,
                                          @RequestHeader Map<String, String> headers,
                                          jakarta.servlet.http.HttpServletRequest httpRequest) {
        // 白名单在验签之前执行：把明显不是微信的请求挡在昂贵的验签/解密之前；未配置时直接放行
        // 参数名用 httpRequest：方法体里已有一个同名的 WechatNotifyRequest 局部变量
        notifyIpGuard.check(httpRequest);
        if (tradeService.isWechatMock()) {
            WechatNotifyRequest request = Json.read(rawBody, WechatNotifyRequest.class);
            if (request == null) {
                throw BizException.badRequest("回调内容为空");
            }
            NotifyResult result = tradeService.handleNotify(request, rawBody);
            return ResponseEntity.ok(result.success() ? Result.ok(result) : Result.fail(result.code(), result.message()));
        }
        try {
            WechatPayNotifyHandler.Notify notify = wechatPayNotifyHandler.parse(headers, rawBody);
            NotifyResult result = tradeService.handleVerifiedNotify(notify.payNo(), notify.transactionId(),
                    notify.amountFen(), notify.currency(), notify.tradeState(), rawBody);
            if (!result.success()) {
                // 业务上拒收（金额不符、支付单不存在等）：仍返回 FAIL 让微信重试并留出人工介入时间
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("code", "FAIL", "message", result.message()));
            }
            return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
        } catch (BizException e) {
            log.warn("微信支付回调被拒 code={} message={}", e.getCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "FAIL", "message", e.getMessage()));
        }
    }
}
