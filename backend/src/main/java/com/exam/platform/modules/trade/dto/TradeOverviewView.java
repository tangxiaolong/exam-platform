package com.exam.platform.modules.trade.dto;

/**
 * 交易与财务的全局概览（管理端顶部指标条的唯一数据源）。
 *
 * <p>五个二级页面顶部那排数字都取这一份：一次请求取回计数与金额合计，
 * 而不是每页各发四五个 {@code pageSize=1} 的分页请求去凑 total。
 * 金额单位统一为分，由前端负责换算成元。</p>
 */
public record TradeOverviewView(
        long orderTotal,
        long orderPendingPayment,
        long orderPaid,
        long orderCompleted,
        long orderClosed,
        long orderRefunded,
        long paidAmountFen,
        long todayPaidCount,
        long todayPaidAmountFen,
        long refundPending,
        long refundSuccess,
        long refundRejected,
        long refundedAmountFen,
        long productOnSale,
        long productOffSale,
        long couponActive,
        long couponStopped,
        long couponClaimed) {
}
