package com.exam.platform.modules.trade;

import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.trade.dto.TradeOverviewView;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.MarketingCoupon;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeRefund;
import com.exam.platform.modules.trade.mapper.TradeStatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易与财务概览：把「订单 / 退款 / 商品 / 券」四组状态计数一次算齐。
 *
 * <p>为什么单独一个服务：四个二级页面都要在顶部显示同一组数字（待审核退款、待支付订单…），
 * 若各页各写一遍聚合，口径迟早会漂——比如某页按 created_at 算「今日成交」、另一页按 paid_at。
 * 这里统一口径，页面只负责展示。</p>
 */
@Service
@RequiredArgsConstructor
public class TradeOverviewService {

    private final TradeStatsMapper statsMapper;

    public TradeOverviewView overview() {
        long tenantId = UserContext.tenantId();

        Map<String, TradeStatsMapper.StatusAgg> orders = index(statsMapper.aggregateOrders(tenantId));
        TradeStatsMapper.StatusAgg today = statsMapper.todayPaid(tenantId);
        Map<String, TradeStatsMapper.StatusAgg> refunds = index(statsMapper.aggregateRefunds(tenantId));
        Map<String, TradeStatsMapper.StatusAgg> coupons = index(statsMapper.aggregateCoupons(tenantId));
        Map<String, TradeStatsMapper.StatusAgg> products = index(statsMapper.aggregateProducts(tenantId));

        long orderTotal = orders.values().stream().mapToLong(a -> nvl(a.getCnt())).sum();
        // 累计成交口径与 TradeOrderMapper.sumPaidAmountFen 保持一致：PAID + COMPLETED
        long paidAmountFen = count(orders, TradeOrder.STATUS_PAID, true) + count(orders, TradeOrder.STATUS_COMPLETED, true);
        long refundedAmountFen = count(refunds, TradeRefund.STATUS_SUCCESS, true);

        return new TradeOverviewView(
                orderTotal,
                count(orders, TradeOrder.STATUS_PENDING_PAYMENT, false),
                count(orders, TradeOrder.STATUS_PAID, false),
                count(orders, TradeOrder.STATUS_COMPLETED, false),
                count(orders, TradeOrder.STATUS_CLOSED, false),
                count(orders, TradeOrder.STATUS_REFUNDED, false) + count(orders, TradeOrder.STATUS_PARTIALLY_REFUNDED, false),
                paidAmountFen,
                today == null ? 0L : nvl(today.getCnt()),
                today == null ? 0L : nvl(today.getAmountFen()),
                count(refunds, TradeRefund.STATUS_APPLIED, false),
                count(refunds, TradeRefund.STATUS_SUCCESS, false),
                count(refunds, TradeRefund.STATUS_REJECTED, false),
                refundedAmountFen,
                count(products, MallProduct.STATUS_ON_SALE, false),
                count(products, MallProduct.STATUS_OFF_SALE, false),
                count(coupons, MarketingCoupon.STATUS_ACTIVE, false),
                count(coupons, MarketingCoupon.STATUS_STOPPED, false),
                count(coupons, MarketingCoupon.STATUS_ACTIVE, true) + count(coupons, MarketingCoupon.STATUS_STOPPED, true));
    }

    private Map<String, TradeStatsMapper.StatusAgg> index(List<TradeStatsMapper.StatusAgg> rows) {
        return rows.stream()
                .filter(row -> row.getStatus() != null)
                .collect(Collectors.toMap(TradeStatsMapper.StatusAgg::getStatus, row -> row, (a, b) -> a));
    }

    /** 取某个状态的计数或金额；{@code amount=true} 时取金额合计。 */
    private long count(Map<String, TradeStatsMapper.StatusAgg> rows, String status, boolean amount) {
        TradeStatsMapper.StatusAgg row = rows.get(status);
        if (row == null) {
            return 0L;
        }
        return amount ? nvl(row.getAmountFen()) : nvl(row.getCnt());
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
