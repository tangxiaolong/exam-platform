package com.exam.platform.modules.learning.dto;

import com.exam.platform.modules.question.model.QuestionView;

import java.time.LocalDateTime;
import java.util.List;

/** 练习会话详情（含待练习题目，不含答案）。 */
public record PracticeStartResponse(
        Long practiceId,
        Long subjectId,
        String mode,
        String status,
        LocalDateTime startedAt,
        Integer totalCount,
        Integer answeredCount,
        Integer correctCount,
        List<QuestionView> questions
) {
}
