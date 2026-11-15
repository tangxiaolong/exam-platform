package com.exam.platform.modules.org.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 额度对账结果（两层一起返回）。
 *
 * <p>给运维一个「现在就对一遍」的入口，而不是只能等凌晨的定时任务。
 * 出问题时的典型用法：先看时间窗版本（快），怀疑是历史遗留再用 {@code fullScan=true} 全量核对（慢）。</p>
 *
 * <p>{@code consistent} 放在 record 组件里而不是写成 {@code isConsistent()} 方法：
 * record 的非组件方法能否被序列化成 JSON 字段取决于 Jackson 版本对 record 的处理方式，
 * 一旦没被收录，前端读到的就是 {@code undefined}——「账一致」与「字段不存在」在 JS 里同样 falsy，
 * 这种静默丢失只能靠字段级断言发现。做成组件，序列化是确定的。</p>
 *
 * @param fullScan             是否全量核对（false 表示只核最近 lookbackDays 天）
 * @param scannedFrom          批次级实际扫描的起点；全量时为空
 * @param scannedTo            批次级实际扫描的终点；全量时为空
 * @param consistent           两层是否都一致（= 两个 count 都为 0）
 */
public record QuotaReconReport(
        boolean fullScan,
        LocalDateTime scannedFrom,
        LocalDateTime scannedTo,
        int accountMismatchCount,
        int batchMismatchCount,
        boolean consistent,
        List<QuotaMismatch> accountMismatches,
        List<QuotaBatchMismatch> batchMismatches
) {
    public static QuotaReconReport of(boolean fullScan, LocalDateTime scannedFrom, LocalDateTime scannedTo,
                                      List<QuotaMismatch> accountMismatches,
                                      List<QuotaBatchMismatch> batchMismatches) {
        return new QuotaReconReport(fullScan, scannedFrom, scannedTo,
                accountMismatches.size(), batchMismatches.size(),
                accountMismatches.isEmpty() && batchMismatches.isEmpty(),
                accountMismatches, batchMismatches);
    }
}
