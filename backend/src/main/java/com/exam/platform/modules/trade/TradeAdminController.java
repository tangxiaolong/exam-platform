package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.TradeOrder;
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

/** 运营后台：商品与订单。 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class TradeAdminController {

    private final TradeService tradeService;
    private final RefundService refundService;

    @GetMapping("/products")
    public Result<PageResult<MallProduct>> products(@RequestParam(required = false) String keyword,
                                                    PageQuery pageQuery) {
        return Result.ok(tradeService.pageProducts(keyword, false, pageQuery));
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

    @GetMapping("/refunds")
    public Result<PageResult<TradeRefund>> refunds(@RequestParam(required = false) String status,
                                                   PageQuery pageQuery) {
        return Result.ok(refundService.pageAll(status, pageQuery));
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
