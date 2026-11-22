package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.trade.entity.MarketingCoupon;
import com.exam.platform.modules.trade.entity.MarketingCouponGrant;
import com.exam.platform.modules.trade.mapper.MarketingCouponGrantMapper;
import com.exam.platform.modules.trade.mapper.MarketingCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠券：领取、校验、抵扣计算、锁定与核销。
 *
 * <p>三处刻意的设计：</p>
 * <ul>
 *   <li><b>抵扣额永远由服务端算</b>：客户端传「抵多少」等于把定价权交出去；</li>
 *   <li><b>抵扣后实付必须 ≥ 1 分</b>：微信不接受 0 元支付单，0 元订单也会让对账与退款失去基准；</li>
 *   <li><b>先锁定再核销</b>：下单锁券、支付成功核销、关单回滚。少了 LOCKED，
 *       同一张券能同时抵两笔订单，而两笔里大概率只有一笔会付款。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final MarketingCouponMapper couponMapper;
    private final MarketingCouponGrantMapper grantMapper;
    private final SysUserMapper userMapper;

    // ---------------------------------------------------------------- 领取

    /** 可领列表：上架、在有效期内、未领完、未超人均。 */
    public List<MarketingCoupon> listClaimable(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<MarketingCoupon> coupons = couponMapper.selectList(Wrappers.<MarketingCoupon>lambdaQuery()
                .eq(MarketingCoupon::getStatus, MarketingCoupon.STATUS_ACTIVE)
                .le(MarketingCoupon::getValidFrom, now)
                .ge(MarketingCoupon::getValidTo, now)
                .orderByAsc(MarketingCoupon::getThresholdFen)
                .orderByDesc(MarketingCoupon::getId));
        return coupons.stream()
                .filter(c -> !limitReached(userId, c))
                .toList();
    }

    /** 我的券：grant 记录合并券面信息（名称/优惠内容），已停发的券也正常展示。 */
    public List<CouponGrantView> listMine(Long userId, String status) {
        List<MarketingCouponGrant> grants = grantMapper.selectList(Wrappers.<MarketingCouponGrant>lambdaQuery()
                .eq(MarketingCouponGrant::getUserId, userId)
                .eq(status != null && !status.isBlank(), MarketingCouponGrant::getStatus, status)
                .orderByDesc(MarketingCouponGrant::getId));
        if (grants.isEmpty()) {
            return List.of();
        }
        Set<Long> couponIds = grants.stream().map(MarketingCouponGrant::getCouponId).collect(Collectors.toSet());
        Map<Long, MarketingCoupon> coupons = couponMapper.selectBatchIds(couponIds).stream()
                .collect(Collectors.toMap(MarketingCoupon::getId, c -> c));
        return grants.stream()
                .map(g -> {
                    MarketingCoupon c = coupons.get(g.getCouponId());
                    return new CouponGrantView(g.getId(), g.getCouponId(),
                            c == null ? "已删除的券" : c.getName(),
                            c == null ? null : c.getType(),
                            c == null ? null : c.getThresholdFen(),
                            c == null ? null : c.getValueFen(),
                            c == null ? null : c.getDiscountPercent(),
                            c == null ? null : c.getMaxDiscountFen(),
                            g.getStatus(), g.getDiscountFen(), g.getGrantedAt(), g.getUsedAt(),
                            c == null ? null : c.getValidTo());
                })
                .toList();
    }

    @Transactional
    public MarketingCouponGrant claim(Long userId, String code) {
        MarketingCoupon coupon = couponMapper.selectOne(Wrappers.<MarketingCoupon>lambdaQuery()
                .eq(MarketingCoupon::getCode, code));
        if (coupon == null) {
            throw BizException.notFound("优惠券");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!coupon.isActive()) {
            throw new BizException("COUPON_STOPPED", "该券已停发");
        }
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidTo())) {
            throw new BizException("COUPON_OUT_OF_PERIOD", "该券不在可领时间内");
        }
        long mine = grantMapper.selectCount(Wrappers.<MarketingCouponGrant>lambdaQuery()
                .eq(MarketingCouponGrant::getUserId, userId)
                .eq(MarketingCouponGrant::getCouponId, coupon.getId()));
        int perUser = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
        if (mine >= perUser) {
            throw new BizException("COUPON_LIMIT_REACHED", "每人限领 " + perUser + " 张");
        }

        // 总量控制必须是一条原子 UPDATE：先 count 再 insert 在并发下会超发
        int rows = couponMapper.update(null, Wrappers.<MarketingCoupon>lambdaUpdate()
                .eq(MarketingCoupon::getId, coupon.getId())
                .apply("(total_quantity = 0 OR claimed_quantity < total_quantity)")
                .setSql("claimed_quantity = claimed_quantity + 1"));
        if (rows == 0) {
            throw new BizException("COUPON_SOLD_OUT", "该券已被领完");
        }

        MarketingCouponGrant grant = new MarketingCouponGrant();
        grant.setCouponId(coupon.getId());
        grant.setUserId(userId);
        grant.setStatus(MarketingCouponGrant.STATUS_UNUSED);
        grant.setGrantedAt(now);
        Entities.forInsert(grant);
        grantMapper.insert(grant);
        log.info("领券成功 userId={} couponId={} code={}", userId, coupon.getId(), code);
        return grant;
    }

    private boolean limitReached(Long userId, MarketingCoupon coupon) {
        if (coupon.getTotalQuantity() != null && coupon.getTotalQuantity() > 0
                && coupon.getClaimedQuantity() != null
                && coupon.getClaimedQuantity() >= coupon.getTotalQuantity()) {
            return true;
        }
        int perUser = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
        long mine = grantMapper.selectCount(Wrappers.<MarketingCouponGrant>lambdaQuery()
                .eq(MarketingCouponGrant::getUserId, userId)
                .eq(MarketingCouponGrant::getCouponId, coupon.getId()));
        return mine >= perUser;
    }

    // ---------------------------------------------------------------- 抵扣

    /**
     * 校验并计算抵扣金额（分）。不满足条件直接抛出，绝不返回 0 让调用方继续下单。
     */
    public long evaluate(Long userId, Long grantId, Long productId, long amountFen) {
        MarketingCouponGrant grant = grantMapper.selectById(grantId);
        if (grant == null || !grant.getUserId().equals(userId)) {
            throw new BizException("COUPON_NOT_FOUND", "优惠券不存在");
        }
        if (!MarketingCouponGrant.STATUS_UNUSED.equals(grant.getStatus())) {
            throw new BizException("COUPON_NOT_USABLE", "优惠券当前不可用：" + grant.getStatus());
        }
        MarketingCoupon coupon = couponMapper.selectById(grant.getCouponId());
        if (coupon == null) {
            throw BizException.notFound("优惠券");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!coupon.isActive()) {
            throw new BizException("COUPON_STOPPED", "该券已停用");
        }
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidTo())) {
            throw new BizException("COUPON_EXPIRED", "该券已过期");
        }
        if (MarketingCoupon.SCOPE_PRODUCT.equals(coupon.getScopeType())
                && !productId.equals(coupon.getScopeId())) {
            throw new BizException("COUPON_SCOPE_MISMATCH", "该券不适用于当前商品");
        }
        long threshold = coupon.getThresholdFen() == null ? 0L : coupon.getThresholdFen();
        if (amountFen < threshold) {
            throw new BizException("COUPON_THRESHOLD_NOT_MET",
                    "还差 " + (threshold - amountFen) + " 分可用该券");
        }
        long discount = compute(coupon, amountFen);
        if (discount <= 0) {
            throw new BizException("COUPON_NO_BENEFIT", "该券在此订单没有抵扣");
        }
        if (discount >= amountFen) {
            // 抵扣完变 0 元：既付不了款，也会让退款与对账失去基准
            throw new BizException("COUPON_AMOUNT_INVALID", "该券抵扣后金额为 0，请调整商品数量");
        }
        return discount;
    }

    private long compute(MarketingCoupon coupon, long amountFen) {
        if (coupon.isDiscount()) {
            int percent = coupon.getDiscountPercent() == null ? 100 : coupon.getDiscountPercent();
            if (percent <= 0 || percent >= 100) {
                throw new BizException("COUPON_CONFIG_INVALID", "折扣券的折扣百分比配置错误：" + percent);
            }
            // 整除向下取整：宁可少抵 1 分，也不能因四舍五入让平台多让利
            long discount = amountFen * (100 - percent) / 100;
            Long max = coupon.getMaxDiscountFen();
            return max != null && max > 0 ? Math.min(discount, max) : discount;
        }
        long value = coupon.getValueFen() == null ? 0L : coupon.getValueFen();
        return Math.min(value, amountFen);
    }

    // ---------------------------------------------------------------- 生命周期

    @Transactional
    public void lock(Long grantId, Long orderId, long discountFen) {
        MarketingCouponGrant update = new MarketingCouponGrant();
        update.setId(grantId);
        update.setStatus(MarketingCouponGrant.STATUS_LOCKED);
        update.setOrderId(orderId);
        update.setDiscountFen(discountFen);
        grantMapper.updateById(update);
    }

    /** 支付成功：锁定 → 已使用。 */
    @Transactional
    public void confirm(Long orderId) {
        MarketingCouponGrant locked = findLocked(orderId);
        if (locked == null) {
            return;
        }
        MarketingCouponGrant update = new MarketingCouponGrant();
        update.setId(locked.getId());
        update.setStatus(MarketingCouponGrant.STATUS_USED);
        update.setUsedAt(LocalDateTime.now());
        grantMapper.updateById(update);
        log.info("优惠券已核销 orderId={} grantId={} discountFen={}",
                orderId, locked.getId(), locked.getDiscountFen());
    }

    /** 订单关闭/超时未付：锁定 → 退回未使用，否则用户会白丢一张券。 */
    @Transactional
    public void release(Long orderId, String reason) {
        MarketingCouponGrant locked = findLocked(orderId);
        if (locked == null) {
            return;
        }
        MarketingCouponGrant update = new MarketingCouponGrant();
        update.setId(locked.getId());
        update.setStatus(MarketingCouponGrant.STATUS_UNUSED);
        grantMapper.updateById(update);
        // order_id 保留：便于追溯「这张券曾被哪笔订单占用过」，不清零
        log.info("优惠券已回滚 orderId={} grantId={} reason={}", orderId, locked.getId(), reason);
    }

    // ---------------------------------------------------------------- 管理端

    /** 管理端券列表：关键词匹配券编码或名称，可按状态筛选。 */
    public PageResult<MarketingCoupon> adminPage(String keyword, String status, PageQuery pageQuery) {
        Page<MarketingCoupon> page = couponMapper.selectPage(pageQuery.toPage(),
                Wrappers.<MarketingCoupon>lambdaQuery()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(MarketingCoupon::getCode, keyword)
                                .or()
                                .like(MarketingCoupon::getName, keyword))
                        .eq(status != null && !status.isBlank(), MarketingCoupon::getStatus, status)
                        .orderByDesc(MarketingCoupon::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 创建券：编码租户内唯一；类型/折扣/有效期/范围在这里统一校验，Trusted 输入。 */
    @Transactional
    public MarketingCoupon adminCreate(CouponSaveRequest request) {
        String code = nullToEmpty(request.code()).trim();
        String name = nullToEmpty(request.name()).trim();
        if (code.isEmpty() || name.isEmpty()) {
            throw new BizException("COUPON_PARAM_INVALID", "券编码与名称必填");
        }
        String type = request.type();
        if (!MarketingCoupon.TYPE_AMOUNT.equals(type) && !MarketingCoupon.TYPE_DISCOUNT.equals(type)) {
            throw new BizException("COUPON_PARAM_INVALID", "券类型只能是 AMOUNT 或 DISCOUNT");
        }
        if (MarketingCoupon.TYPE_DISCOUNT.equals(type)) {
            int percent = request.discountPercent() == null ? 100 : request.discountPercent();
            if (percent <= 0 || percent >= 100) {
                throw new BizException("COUPON_PARAM_INVALID", "折扣百分比必须在 1-99 之间（85 表示 85 折）");
            }
        } else if (request.valueFen() == null || request.valueFen() <= 0) {
            throw new BizException("COUPON_PARAM_INVALID", "满减券的抵扣金额必须大于 0");
        }
        if (request.validFrom() == null || request.validTo() == null
                || request.validFrom().isAfter(request.validTo())) {
            throw new BizException("COUPON_PARAM_INVALID", "有效期起止时间不合法");
        }
        String scopeType = request.scopeType() == null ? MarketingCoupon.SCOPE_ALL : request.scopeType();
        if (MarketingCoupon.SCOPE_PRODUCT.equals(scopeType)
                && (request.scopeId() == null || request.scopeId() <= 0)) {
            throw new BizException("COUPON_PARAM_INVALID", "指定商品的券必须提供商品ID");
        }
        long exists = couponMapper.selectCount(Wrappers.<MarketingCoupon>lambdaQuery()
                .eq(MarketingCoupon::getCode, code));
        if (exists > 0) {
            throw new BizException("COUPON_CODE_EXISTS", "券编码已存在：" + code);
        }

        MarketingCoupon coupon = new MarketingCoupon();
        coupon.setCode(code);
        coupon.setName(name);
        coupon.setType(type);
        coupon.setThresholdFen(request.thresholdFen() == null ? 0L : request.thresholdFen());
        coupon.setValueFen(request.valueFen() == null ? 0L : request.valueFen());
        coupon.setDiscountPercent(MarketingCoupon.TYPE_DISCOUNT.equals(type)
                ? request.discountPercent() : 0);
        coupon.setMaxDiscountFen(request.maxDiscountFen());
        coupon.setTotalQuantity(request.totalQuantity() == null ? 0 : request.totalQuantity());
        coupon.setPerUserLimit(request.perUserLimit() == null ? 1 : request.perUserLimit());
        coupon.setClaimedQuantity(0);
        coupon.setScopeType(scopeType);
        coupon.setScopeId(MarketingCoupon.SCOPE_PRODUCT.equals(scopeType) ? request.scopeId() : null);
        coupon.setValidFrom(request.validFrom());
        coupon.setValidTo(request.validTo());
        coupon.setStatus(MarketingCoupon.STATUS_ACTIVE);
        Entities.forInsert(coupon);
        couponMapper.insert(coupon);
        log.info("创建优惠券 id={} code={} type={}", coupon.getId(), code, type);
        return coupon;
    }

    /** 启用 / 停发券：只允许 ACTIVE 与 STOPPED 两态切换。 */
    @Transactional
    /** 管理端查看某张券的领取记录：持券人、状态、核销抵扣、关联订单。 */
    public PageResult<CouponGrantAdminView> adminGrants(Long couponId, PageQuery pageQuery) {
        var page = grantMapper.selectPage(pageQuery.toPage(),
                Wrappers.<MarketingCouponGrant>lambdaQuery()
                        .eq(MarketingCouponGrant::getCouponId, couponId)
                        .orderByDesc(MarketingCouponGrant::getId));
        List<MarketingCouponGrant> records = page.getRecords();
        Map<Long, String> usernames = new HashMap<>();
        if (!records.isEmpty()) {
            Set<Long> userIds = records.stream()
                    .map(MarketingCouponGrant::getUserId)
                    .collect(Collectors.toSet());
            for (SysUser user : userMapper.selectBatchIds(userIds)) {
                usernames.put(user.getId(), user.getUsername());
            }
        }
        List<CouponGrantAdminView> views = records.stream()
                .map(grant -> new CouponGrantAdminView(
                        grant.getId(), grant.getUserId(),
                        usernames.getOrDefault(grant.getUserId(), "已注销用户"),
                        grant.getStatus(), grant.getDiscountFen(), grant.getOrderId(),
                        grant.getGrantedAt(), grant.getUsedAt()))
                .toList();
        return PageResult.of(views, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public MarketingCoupon adminUpdateStatus(Long id, String status) {
        if (!MarketingCoupon.STATUS_ACTIVE.equals(status) && !MarketingCoupon.STATUS_STOPPED.equals(status)) {
            throw new BizException("COUPON_STATUS_INVALID", "状态只能是 ACTIVE 或 STOPPED");
        }
        MarketingCoupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw BizException.notFound("优惠券");
        }
        MarketingCoupon update = new MarketingCoupon();
        update.setId(id);
        update.setStatus(status);
        couponMapper.updateById(update);
        coupon.setStatus(status);
        log.info("变更优惠券状态 id={} status={}", id, status);
        return coupon;
    }

    /**
     * 到期券失效：把「有效期已过」但仍未使用的持券记录翻为 EXPIRED（幂等）。
     *
     * <p>不动 LOCKED（锁定的券正被未结束订单占用，回滚是订单链路的职责）、
     * 不动 USED/EXPIRED（前者是历史事实）——只翻转 UNUSED。没有这一步，
     * 「我的券」会永远显示过期券「未使用」，下单时再被 COUPON_EXPIRED 拒绝。</p>
     */
    @Transactional
    public int expireOverdue() {
        Set<Long> expiredCouponIds = couponMapper.selectList(Wrappers.<MarketingCoupon>lambdaQuery()
                        .lt(MarketingCoupon::getValidTo, LocalDateTime.now()))
                .stream()
                .map(MarketingCoupon::getId)
                .collect(Collectors.toSet());
        if (expiredCouponIds.isEmpty()) {
            return 0;
        }
        int rows = grantMapper.update(null, Wrappers.<MarketingCouponGrant>lambdaUpdate()
                .eq(MarketingCouponGrant::getStatus, MarketingCouponGrant.STATUS_UNUSED)
                .in(MarketingCouponGrant::getCouponId, expiredCouponIds)
                .set(MarketingCouponGrant::getStatus, MarketingCouponGrant.STATUS_EXPIRED));
        if (rows > 0) {
            log.info("优惠券到期失效 {} 张", rows);
        }
        return rows;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private MarketingCouponGrant findLocked(Long orderId) {
        return grantMapper.selectOne(Wrappers.<MarketingCouponGrant>lambdaQuery()
                .eq(MarketingCouponGrant::getOrderId, orderId)
                .eq(MarketingCouponGrant::getStatus, MarketingCouponGrant.STATUS_LOCKED)
                .last("LIMIT 1"));
    }
}
