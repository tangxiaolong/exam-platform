package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.modules.trade.dto.TradeOverviewView;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeOrderItem;
import com.exam.platform.modules.trade.entity.TradeRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运营后台：商品、订单、退款，以及四者共用的概览指标。 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class TradeAdminController {

    private final TradeService tradeService;
    private final RefundService refundService;
    private final TradeOverviewService tradeOverviewService;

    /**
     * 概览指标：订单 / 退款 / 商品 / 券四组状态计数。
     *
     * <p>放在商品、订单、退款、券四个页面的顶部共用——各页各写一套聚合，
     * 「今日成交」按 created_at 还是 paid_at 迟早会不一致。</p>
     */
    @GetMapping("/trade/overview")
    public Result<TradeOverviewView> overview() {
        return Result.ok(tradeOverviewService.overview());
    }

    /**
     * 商品列表。{@code status} / {@code type} 是可选的精确筛选——
     * 之前运营端只能按关键词查，筛「已下架」或「只看会员」都得自己翻页。
     */
    @GetMapping("/products")
    public Result<PageResult<MallProduct>> products(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String type,
                                                    PageQuery pageQuery) {
        return Result.ok(tradeService.pageProducts(keyword, status, type, pageQuery));
    }

    @PostMapping("/products")
    @Audited(action = "PRODUCT_CREATE", target = "'mall_product:' + #result.data.id",
            detail = "#result.data.name")
    public Result<MallProduct> createProduct(@RequestBody MallProduct product) {
        return Result.ok(tradeService.saveProduct(product));
    }

    @PutMapping("/products/{id}")
    @Audited(action = "PRODUCT_UPDATE", target = "'mall_product:' + #id", detail = "#result.data.name")
    public Result<MallProduct> updateProduct(@PathVariable Long id, @RequestBody MallProduct product) {
        return Result.ok(tradeService.updateProduct(id, product));
    }

    @DeleteMapping("/products/{id}")
    @Audited(action = "PRODUCT_DELETE", target = "'mall_product:' + #id")
    public Result<Void> deleteProduct(@PathVariable Long id) {
        tradeService.deleteProduct(id);
        return Result.ok();
    }

    @GetMapping("/orders")
    public Result<PageResult<TradeOrder>> orders(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 PageQuery pageQuery) {
        return Result.ok(tradeService.pageAllOrders(status, keyword, pageQuery));
    }

    /** 订单明细：退款按行退，运营核对「这一单买的是哪几行、各自退了多少」需要它。 */
    @GetMapping("/orders/{id}/items")
    public Result<List<TradeOrderItem>> orderItems(@PathVariable Long id) {
        return Result.ok(tradeService.listOrderItems(id));
    }

    /** 退款列表：{@code keyword} 同时匹配退款单号与订单号，客服手上通常只有其中一个。 */
    @GetMapping("/refunds")
    public Result<PageResult<TradeRefund>> refunds(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   PageQuery pageQuery) {
        return Result.ok(refundService.pageAll(status, keyword, pageQuery));
    }

    /** 审核通过：调用退款 + 回收未使用权益（幂等）。 */
    @PostMapping("/refunds/{id}/approve")
    @Audited(action = "REFUND_APPROVE", target = "'trade_refund:' + #id", detail = "#result.data.refundNo")
    public Result<TradeRefund> approveRefund(@PathVariable Long id,
                                             @RequestParam(required = false) String remark) {
        return Result.ok(refundService.approve(id, remark));
    }

    @PostMapping("/refunds/{id}/reject")
    @Audited(action = "REFUND_REJECT", target = "'trade_refund:' + #id", detail = "#remark")
    public Result<TradeRefund> rejectRefund(@PathVariable Long id,
                                            @RequestParam(required = false) String remark) {
        return Result.ok(refundService.reject(id, remark));
    }
}
