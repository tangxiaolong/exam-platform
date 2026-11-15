package com.exam.platform.modules.report.dto;

import java.time.LocalDateTime;

/** 导出任务视图（含下载地址）。 */
public record ExportTaskView(
        Long id,
        String taskNo,
        String type,
        String status,
        String fileName,
        String downloadUrl,
        Integer rowCount,
        String errorMsg,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
}
