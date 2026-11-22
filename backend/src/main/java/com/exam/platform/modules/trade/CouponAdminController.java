package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.modules.trade.entity.MarketingCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台：优惠券管理。
 *
 * <p>此前券系统只有考生侧的领取与抵扣，创建券只能手工写库——运营无法自助发券。
 * 本端点补齐创建、列表与启停；扣减与核销逻辑仍在 {@link CouponService} 考生侧路径，
 * 两条路径共用同一套校验（active/有效期/限量），不会出现「后台建了却无法领」的分叉。</p>
 */
@RestController
@RequestMapping("/v1/admin/coupons")
@RequiredArgsConstructor
public class CouponAdminController {

    private final CouponService couponService;

    /** 券列表：关键词（编码/名称）+ 状态筛选。 */
    @GetMapping
    public Result<PageResult<MarketingCoupon>> page(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    PageQuery pageQuery) {
        return Result.ok(couponService.adminPage(keyword, status, pageQuery));
    }

    /** 创建券：金额单位为分；折扣券折扣百分比 85 表示 85 折。 */
    @PostMapping
    @Audited(action = "COUPON_CREATE", target = "'marketing_coupon:' + #request.code()",
            detail = "'code=' + #request.code()")
    public Result<MarketingCoupon> create(@RequestBody CouponSaveRequest request) {
        return Result.ok(couponService.adminCreate(request));
    }

    /** 启用 / 停发（ACTIVE / STOPPED）。 */
    /** 领取记录：这张券被谁领了、用到哪一步（分页）。 */
    @GetMapping("/{id}/grants")
    public Result<PageResult<CouponGrantAdminView>> grants(@PathVariable Long id, PageQuery pageQuery) {
        return Result.ok(couponService.adminGrants(id, pageQuery));
    }

    @PutMapping("/{id}/status")
    @Audited(action = "COUPON_STATUS", target = "'marketing_coupon:' + #id",
            detail = "'status=' + #status")
    public Result<MarketingCoupon> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return Result.ok(couponService.adminUpdateStatus(id, status));
    }
}
