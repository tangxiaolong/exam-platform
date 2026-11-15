package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.trade.dto.ReconAnomalyView;
import com.exam.platform.modules.trade.dto.ReconReportView;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeReconAnomaly;
import com.exam.platform.modules.trade.entity.TradeReconReport;
import com.exam.platform.modules.trade.mapper.ReconCheckMapper;
import com.exam.platform.modules.trade.mapper.TradeReconAnomalyMapper;
import com.exam.platform.modules.trade.mapper.TradeReconReportMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付 / 订单 / 权益三方对账。
 *
 * <p>支付链路是「回调落流水 → 验签 → 置订单已支付 → 发权益」的多步写，单次回调的事务
 * 覆盖不了跨步骤失败，而这类不一致在业务上表现为「钱到了、权益没到」，只能靠用户投诉
 * 才发现。对账把这件事前移成每天主动检出。</p>
 *
 * <p>三个刻意的边界：</p>
 * <ol>
 *   <li><b>不自动修复</b>：涉及资金与权益，自动补偿做错一次就是把错账写进账本，首期只检出＋留痕；</li>
 *   <li><b>留宽限期</b>：「已支付但无权益」在回调后几秒内是正常的，必须排除掉，
 *       否则每次对账都会误报一批刚付款的订单；</li>
 *   <li><b>单类异常封顶</b>：异常数量用 {@code LIMIT} 封顶而不是全量落库，
 *       避免一次故障把明细表写爆——报告里的条数足以说明问题规模。</li>
 * </ol>
 */
@Slf4j
@Service
public class ReconciliationService {

    /** 单类异常最多落库条数。 */
    private static final int MAX_ROWS_PER_CHECK = 200;
    /** 明细查询上限，与落库上限保持一致。 */
    private static final int MAX_ANOMALY_ROWS = 500;
    /** 导出上限：一份交给财务的清单不该有几十万行，超出的按月分次导。 */
    private static final int MAX_EXPORT_ROWS = 5_000;
    /** 处置说明长度上限，与列定义一致。 */
    private static final int MAX_REMARK_LENGTH = 200;

    private final ReconCheckMapper checkMapper;
    private final TradeReconReportMapper reportMapper;
    private final TradeReconAnomalyMapper anomalyMapper;
    private final MeterRegistry meterRegistry;
    private final int graceMinutes;

    public ReconciliationService(ReconCheckMapper checkMapper,
                                 TradeReconReportMapper reportMapper,
                                 TradeReconAnomalyMapper anomalyMapper,
                                 MeterRegistry meterRegistry,
                                 @Value("${app.reconciliation.paid-grace-minutes:10}") int graceMinutes) {
        this.checkMapper = checkMapper;
        this.reportMapper = reportMapper;
        this.anomalyMapper = anomalyMapper;
        this.meterRegistry = meterRegistry;
        this.graceMinutes = graceMinutes;
    }

    @Transactional
    public ReconReportView run(String scope) {
        TradeReconReport report = new TradeReconReport();
        report.setReportNo(BizNo.reconNo());
        report.setScope(scope);
        report.setStatus(TradeReconReport.STATUS_PASSED);
        report.setCheckedOrders(0);
        report.setCheckedPayments(0);
        report.setCheckedAccounts(0);
        report.setAnomalyCount(0);
        report.setStartedAt(LocalDateTime.now());
        Entities.forInsert(report);
        // 先落报告再检查：检查过程若因数据异常炸掉，至少留下一条「跑过但失败」的记录，
        // 否则「任务没跑」和「跑了没发现问题」在库里长得一模一样
        reportMapper.insert(report);

        List<TradeReconAnomaly> anomalies;
        try {
            anomalies = collect(report);
        } catch (RuntimeException e) {
            log.error("对账执行失败 reportNo={}", report.getReportNo(), e);
            report.setStatus(TradeReconReport.STATUS_FAILED);
            report.setErrorMsg(truncate(e.getMessage(), 480));
            report.setFinishedAt(LocalDateTime.now());
            reportMapper.updateById(report);
            return toView(report, List.of());
        }

        report.setCheckedOrders(checkMapper.countOrders());
        report.setCheckedPayments(checkMapper.countPayments());
        report.setCheckedAccounts(checkMapper.countAccounts());
        report.setAnomalyCount(anomalies.size());
        report.setStatus(anomalies.isEmpty()
                ? TradeReconReport.STATUS_PASSED : TradeReconReport.STATUS_ANOMALY);
        report.setFinishedAt(LocalDateTime.now());
        reportMapper.updateById(report);

        if (!anomalies.isEmpty()) {
            log.warn("对账发现 {} 条不一致 reportNo={}", anomalies.size(), report.getReportNo());
        }
        return toView(report, anomalies);
    }

    public ReconReportView latest() {
        TradeReconReport report = reportMapper.selectOne(Wrappers.<TradeReconReport>lambdaQuery()
                .orderByDesc(TradeReconReport::getId)
                .last("LIMIT 1"));
        if (report == null) {
            return null;
        }
        return toView(report, anomaliesOf(report.getId()));
    }

    /**
     * 对一条异常做处置：标记已处理 / 确认误报。
     *
     * <p>只允许从 PENDING 出发单向流转，重复处置直接报错而不是就地覆盖：
     * 两个人同时看同一批异常时，后者覆盖前者会让「谁修了什么」彻底失真；
     * 而按异常记录做处置（而非按 biz_id 做全局处置），天然按报告轮次闭环，
     * 下一轮对账若还检得出问题，会是一条新的待处理记录。</p>
     */
    @Transactional
    public ReconAnomalyView handle(Long anomalyId, String status, String remark) {
        if (!TradeReconAnomaly.HANDLE_STATUSES.contains(status)) {
            throw new BizException("TRADE_RECON_HANDLE_STATUS_INVALID", "不支持的处置状态：" + status);
        }
        TradeReconAnomaly item = anomalyMapper.selectById(anomalyId);
        if (item == null) {
            throw new BizException("TRADE_RECON_ANOMALY_NOT_FOUND", "对账异常不存在或已删除");
        }
        if (!TradeReconAnomaly.HANDLE_PENDING.equals(item.getHandleStatus())) {
            throw new BizException("TRADE_RECON_ANOMALY_ALREADY_HANDLED",
                    "该异常已处置为 " + item.getHandleStatus() + "，如需变更请按报告轮次重新对账");
        }
        item.setHandleStatus(status);
        item.setHandleRemark(truncate(remark, MAX_REMARK_LENGTH));
        item.setHandledBy(UserContext.userId());
        item.setHandledAt(LocalDateTime.now());
        anomalyMapper.updateById(item);
        return toView(item);
    }

    /**
     * 跨报告轮次的待处置清单。
     *
     * <p>处置本身按报告轮次闭环（一轮检出的异常处置完就结束），但运营需要的恰恰是相反的视角：
     * 「平台上现在还有多少笔钱没对上」。这个数字只能跨所有历史报告看——只查最近一份报告会漏掉
     * 「上一轮检出、这一轮因为数据变了没再检出（但问题并没修）」的情况。</p>
     */
    public PageResult<ReconAnomalyView> pageAnomalies(String handleStatus, String bizType,
                                                      PageQuery pageQuery) {
        Page<TradeReconAnomaly> page = anomalyMapper.selectPage(pageQuery.toPage(),
                anomalyQuery(handleStatus, bizType));
        return PageResult.of(page.getRecords().stream().map(this::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 导出异常清单（CSV）。
     *
     * <p>财务按月对账要的是一份能直接打开的文件，而不是让运营对着页面抄。
     * 导出上限 {@code MAX_EXPORT_ROWS} 与告警规则里的 {@code 26h} 滑窗是两种口径：
     * 这里要的是「历史全量看得见」，所以不做时间裁剪，只做条数封顶防止拖垮内存。</p>
     */
    public byte[] exportAnomalies(String handleStatus, String bizType) {
        List<TradeReconAnomaly> rows = anomalyMapper.selectList(
                anomalyQuery(handleStatus, bizType).last("LIMIT " + MAX_EXPORT_ROWS));
        List<String[]> csvRows = new ArrayList<>();
        csvRows.add(new String[]{"异常ID", "报告ID", "检出类型", "业务类型", "业务编号",
                "期望值", "实际值", "详情", "处置状态", "处置说明", "处置人", "检出时间", "处置时间"});
        for (TradeReconAnomaly item : rows) {
            csvRows.add(new String[]{
                    str(item.getId()), str(item.getReportId()), item.getAnomalyType(), item.getBizType(),
                    item.getBizNo(), item.getExpectedValue(), item.getActualValue(), item.getDetail(),
                    item.getHandleStatus(), item.getHandleRemark(), str(item.getHandledBy()),
                    str(item.getCreatedAt()), str(item.getHandledAt())});
        }
        return csv(csvRows).getBytes(StandardCharsets.UTF_8);
    }

    public List<String> recentReportNos(int limit) {
        return reportMapper.selectList(Wrappers.<TradeReconReport>lambdaQuery()
                        .orderByDesc(TradeReconReport::getId)
                        .last("LIMIT " + Math.max(limit, 1)))
                .stream().map(TradeReconReport::getReportNo).toList();
    }

    private List<TradeReconAnomaly> collect(TradeReconReport report) {
        LocalDateTime before = LocalDateTime.now().minusMinutes(graceMinutes);
        List<TradeReconAnomaly> result = new ArrayList<>();

        for (ReconCheckMapper.OrderWithoutEntitlementRow row
                : checkMapper.findOrdersWithoutEntitlement(TradeOrder.STATUS_PAID, before, MAX_ROWS_PER_CHECK)) {
            result.add(anomaly(report, TradeReconAnomaly.TYPE_ORDER_PAID_WITHOUT_ENTITLEMENT,
                    TradeReconAnomaly.BIZ_ORDER, row.getOrderId(), row.getOrderNo(),
                    "存在 GRANT 权益流水", "无 GRANT 权益流水",
                    "订单状态=" + row.getStatus() + "，应付 " + row.getPayAmountFen()
                            + " 分，支付时间 " + row.getPaidAt()));
        }

        for (ReconCheckMapper.OrderWithoutEntitlementRow row
                : checkMapper.findOrdersWithoutEntitlement(TradeOrder.STATUS_COMPLETED, before, MAX_ROWS_PER_CHECK)) {
            result.add(anomaly(report, TradeReconAnomaly.TYPE_ORDER_COMPLETED_WITHOUT_ENTITLEMENT,
                    TradeReconAnomaly.BIZ_ORDER, row.getOrderId(), row.getOrderNo(),
                    "已完成订单应有 GRANT 流水", "无 GRANT 流水",
                    "订单状态=" + row.getStatus() + "，应付 " + row.getPayAmountFen() + " 分"));
        }

        for (ReconCheckMapper.PaymentOrderStatusRow row
                : checkMapper.findPaidPaymentsWithUnpaidOrder(MAX_ROWS_PER_CHECK)) {
            // 用订单维度而不是支付单维度：要修的是「订单没置为已支付」，
            // 运营拿着订单号就能直接定位并手工补状态
            result.add(anomaly(report, TradeReconAnomaly.TYPE_PAYMENT_PAID_ORDER_NOT_PAID,
                    TradeReconAnomaly.BIZ_ORDER, row.getOrderId(), row.getOrderNo(),
                    "订单状态 PAID/COMPLETED", "订单状态 " + row.getOrderStatus(),
                    "支付单 " + row.getPayNo() + " 渠道已确认成功，但订单未推进"));
        }

        for (ReconCheckMapper.AmountMismatchRow row
                : checkMapper.findPaymentAmountMismatch(MAX_ROWS_PER_CHECK)) {
            result.add(anomaly(report, TradeReconAnomaly.TYPE_PAYMENT_AMOUNT_MISMATCH,
                    TradeReconAnomaly.BIZ_PAYMENT, row.getPaymentId(), row.getPayNo(),
                    "订单应付 " + row.getOrderPayAmountFen() + " 分",
                    "支付单金额 " + row.getPaymentAmountFen() + " 分",
                    "已支付金额与订单应付不一致，需核对渠道流水"));
        }

        for (ReconCheckMapper.AccountDriftRow row : checkMapper.findAccountLedgerDrift(MAX_ROWS_PER_CHECK)) {
            result.add(anomaly(report, TradeReconAnomaly.TYPE_ACCOUNT_LEDGER_DRIFT,
                    TradeReconAnomaly.BIZ_ACCOUNT, row.getAccountId(), null,
                    "余额 " + (row.getTotalAmount() - row.getUsedAmount()) + " = 流水累计 " + row.getLedgerSum(),
                    "余额 " + (row.getTotalAmount() - row.getUsedAmount()) + " ≠ 流水累计 " + row.getLedgerSum(),
                    "总量=" + row.getTotalAmount() + "，已用=" + row.getUsedAmount()));
        }

        for (TradeReconAnomaly item : result) {
            anomalyMapper.insert(item);
            // 检出即打点：对账的价值在于「不用等投诉」，指标进告警规则后，
            // 异常会在下一次对账后主动找人，而不是等人去点开报告
            meterRegistry.counter("app.recon.anomaly", "type", item.getAnomalyType()).increment();
        }
        return result;
    }

    private TradeReconAnomaly anomaly(TradeReconReport report, String type, String bizType, Long bizId,
                                      String bizNo, String expected, String actual, String detail) {
        TradeReconAnomaly item = new TradeReconAnomaly();
        item.setReportId(report.getId());
        item.setAnomalyType(type);
        item.setBizType(bizType);
        item.setBizId(bizId);
        item.setBizNo(bizNo);
        item.setExpectedValue(truncate(expected, 128));
        item.setActualValue(truncate(actual, 128));
        item.setDetail(truncate(detail, 500));
        // 显式声明待处置：不依赖数据库默认值，避免将来插入路径变更时静默漏掉状态
        item.setHandleStatus(TradeReconAnomaly.HANDLE_PENDING);
        Entities.forInsert(item);
        return item;
    }

    /**
     * 异常清单的公共查询条件。
     *
     * <p>按 id 倒序：待处置清单的天然顺序是「新检出的在前」，运营从最新一轮开始看。</p>
     */
    private LambdaQueryWrapper<TradeReconAnomaly> anomalyQuery(String handleStatus, String bizType) {
        LambdaQueryWrapper<TradeReconAnomaly> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(handleStatus)) {
            wrapper.eq(TradeReconAnomaly::getHandleStatus, handleStatus.trim());
        }
        if (StringUtils.hasText(bizType)) {
            wrapper.eq(TradeReconAnomaly::getBizType, bizType.trim());
        }
        return wrapper.orderByDesc(TradeReconAnomaly::getId);
    }

    private List<TradeReconAnomaly> anomaliesOf(Long reportId) {
        return anomalyMapper.selectList(Wrappers.<TradeReconAnomaly>lambdaQuery()
                .eq(TradeReconAnomaly::getReportId, reportId)
                .orderByAsc(TradeReconAnomaly::getId)
                .last("LIMIT " + MAX_ANOMALY_ROWS));
    }

    private ReconReportView toView(TradeReconReport report, List<TradeReconAnomaly> anomalies) {
        List<ReconAnomalyView> views = anomalies.stream().map(this::toView).toList();
        return new ReconReportView(report.getReportNo(), report.getScope(), report.getStatus(),
                nvl(report.getCheckedOrders()), nvl(report.getCheckedPayments()), nvl(report.getCheckedAccounts()),
                nvl(report.getAnomalyCount()), report.getStartedAt(), report.getFinishedAt(),
                report.getErrorMsg(), views);
    }

    private ReconAnomalyView toView(TradeReconAnomaly item) {
        return new ReconAnomalyView(item.getId(), item.getAnomalyType(), item.getBizType(), item.getBizId(),
                item.getBizNo(), item.getExpectedValue(), item.getActualValue(), item.getDetail(),
                item.getHandleStatus(), item.getHandleRemark(), item.getHandledBy(), item.getHandledAt());
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------- CSV（与报表导出口径一致）

    private String csv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM，避免 Excel 打开中文乱码
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String str(Object value) {
        if (value == null) {
            return "";
        }
        // 时间统一成「空格分隔」的可读形式，`2026-06-16T02:00:00` 在 Excel 里不好认
        if (value instanceof LocalDateTime time) {
            return time.toString().replace('T', ' ');
        }
        return value.toString();
    }
}
