package com.exam.platform.modules.trade.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 对账报告视图：一次检查的范围、结论与异常明细。 */
public record ReconReportView(
        String reportNo,
        String scope,
        String status,
        int checkedOrders,
        int checkedPayments,
        int checkedAccounts,
        int anomalyCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMsg,
        List<ReconAnomalyView> anomalies
) {
}
