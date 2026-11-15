package com.exam.platform.modules.trade;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.modules.trade.dto.ReconAnomalyView;
import com.exam.platform.modules.trade.dto.ReconReportView;
import com.exam.platform.modules.trade.entity.TradeReconReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端对账：人工触发一次检查、读取最近结果，以及对检出的异常做处置闭环。 */
@RestController
@RequestMapping("/v1/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/runs")
    @Audited(action = "TRADE_RECON_RUN",
            target = "'trade_recon:' + #result.data.reportNo()",
            detail = "'status=' + #result.data.status() + ', anomalies=' + #result.data.anomalyCount()")
    public Result<ReconReportView> run() {
        return Result.ok(reconciliationService.run(TradeReconReport.SCOPE_MANUAL));
    }

    @GetMapping("/runs/latest")
    public Result<ReconReportView> latest() {
        return Result.ok(reconciliationService.latest());
    }

    @GetMapping("/runs/recent")
    public Result<List<String>> recent() {
        return Result.ok(reconciliationService.recentReportNos(20));
    }

    /**
     * 处置一条异常。涉及资金，处置本身也是高风险操作，因此同样留审计：
     * 事后追「这条异常是谁标记已处理的、当时写了什么理由」全靠这条记录。
     */
    @PostMapping("/anomalies/{id}/handle")
    @Audited(action = "TRADE_RECON_HANDLE",
            target = "'trade_recon_anomaly:' + #id",
            detail = "'status=' + #status + ', remark=' + #handleRemark")
    public Result<ReconAnomalyView> handle(@PathVariable Long id,
                                           @RequestParam String status,
                                           @RequestParam(required = false, name = "remark") String handleRemark) {
        return Result.ok(reconciliationService.handle(id, status, handleRemark));
    }

    /**
     * 跨报告轮次的异常清单。
     *
     * <p>与 {@code /runs/latest} 的分工：那个回答「最近一次对账查出了什么」，
     * 这个回答「平台上现在还有多少笔没对上」——后者必须跨轮次，否则上一轮检出、
     * 这一轮因数据变动没再检出的问题会从视野里消失。</p>
     */
    @GetMapping("/anomalies")
    public Result<PageResult<ReconAnomalyView>> pageAnomalies(
            @RequestParam(required = false) String handleStatus,
            @RequestParam(required = false) String bizType,
            PageQuery pageQuery) {
        return Result.ok(reconciliationService.pageAnomalies(handleStatus, bizType, pageQuery));
    }

    /** 导出一份给财务的 CSV，运营不用再对着页面抄。 */
    @GetMapping("/anomalies/export")
    @Audited(action = "TRADE_RECON_EXPORT", target = "'trade_recon_anomalies'",
            detail = "'handleStatus=' + #handleStatus + ', bizType=' + #bizType")
    public ResponseEntity<byte[]> exportAnomalies(
            @RequestParam(required = false) String handleStatus,
            @RequestParam(required = false) String bizType) {
        byte[] csv = reconciliationService.exportAnomalies(handleStatus, bizType);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recon-anomalies.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }
}
