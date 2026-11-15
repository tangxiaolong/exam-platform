package com.exam.platform.modules.exam.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试记录概要。 */
public record AttemptView(
        Long attemptId,
        Long sessionId,
        String sessionName,
        String status,
        LocalDateTime startedAt,
        LocalDateTime deadlineAt,
        LocalDateTime submittedAt,
        BigDecimal totalScore
) {
}
