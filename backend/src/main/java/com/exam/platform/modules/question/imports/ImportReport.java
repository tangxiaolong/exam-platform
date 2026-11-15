package com.exam.platform.modules.question.imports;

import java.util.List;

/** 题目批量导入结果：逐行明细 + 汇总，对齐「导入成功/失败行均有结果报告」的验收要求。 */
public record ImportReport(
        /** 文件总行数（不含表头与空行） */
        int totalRows,
        int successCount,
        int failureCount,
        /** 是否整批回滚（全部成功才落库，避免半截数据） */
        boolean committed,
        List<RowResult> rows
) {

    public static ImportReport rejected(int totalRows, List<RowResult> rows) {
        int failed = (int) rows.stream().filter(r -> r.status() == RowStatus.FAILED).count();
        return new ImportReport(totalRows, totalRows - failed, failed, false, rows);
    }

    public static ImportReport committed(int totalRows, List<RowResult> rows) {
        return new ImportReport(totalRows, totalRows, 0, true, rows);
    }

    /** 单行结果。 */
    public record RowResult(
            /** 文件中的行号，从 1 开始并包含表头，便于运营对照 Excel 定位 */
            int lineNo,
            RowStatus status,
            String errorCode,
            String errorMessage,
            Long questionId,
            String stem
    ) {

        public static RowResult success(int lineNo, Long questionId, String stem) {
            return new RowResult(lineNo, RowStatus.SUCCESS, null, null, questionId, stem);
        }

        public static RowResult failure(int lineNo, String errorCode, String errorMessage, String stem) {
            return new RowResult(lineNo, RowStatus.FAILED, errorCode, errorMessage, null, stem);
        }
    }

    /** 行状态。 */
    public enum RowStatus {
        SUCCESS,
        FAILED
    }
}
