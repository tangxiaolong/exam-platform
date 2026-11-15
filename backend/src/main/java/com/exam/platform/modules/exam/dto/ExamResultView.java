package com.exam.platform.modules.exam.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 成绩详情。 */
public record ExamResultView(
        Long attemptId,
        Long sessionId,
        String sessionName,
        BigDecimal totalScore,
        Integer fullScore,
        Integer correctCount,
        Integer questionCount,
        String status,
        Integer scoringVersion,
        LocalDateTime submittedAt,
        LocalDateTime publishedAt,
        List<ResultItem> items
) {
}
