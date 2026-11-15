package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 对账报告：一次对账检查的范围与结论。
 *
 * <p>报告本身只记录「查了多少、发现几条」，每条异常落在 {@link TradeReconAnomaly}，
 * 便于按异常类型统计趋势（例如某天 AMOUNT_MISMATCH 突然变多，往往意味着渠道侧出问题）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_recon_report")
public class TradeReconReport extends BaseEntity {

    public static final String SCOPE_MANUAL = "MANUAL";
    public static final String SCOPE_SCHEDULED = "SCHEDULED";

    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_ANOMALY = "ANOMALY";
    public static final String STATUS_FAILED = "FAILED";

    private String reportNo;
    private String scope;
    private Integer checkedOrders;
    private Integer checkedPayments;
    private Integer checkedAccounts;
    private Integer anomalyCount;
    private String status;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
