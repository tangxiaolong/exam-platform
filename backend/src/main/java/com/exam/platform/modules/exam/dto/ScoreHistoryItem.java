package com.exam.platform.modules.exam.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 本人成绩历史中的一条记录（成绩单「成绩趋势/历史对比」用）。
 *
 * <p>只包含已判分或已发布成绩的答卷：阅卷中的成绩不完整，混进趋势里会让曲线失真。
 * 带上 {@code fullScore} 是为了把不同满分的场次折算成百分比后再比较。</p>
 */
public record ScoreHistoryItem(
        Long attemptId,
        Long sessionId,
        String sessionName,
        BigDecimal totalScore,
        Integer fullScore,
        LocalDateTime submittedAt,
        String status
) {
}
