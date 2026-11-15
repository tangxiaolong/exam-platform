package com.exam.platform.modules.learning.dto;

import com.exam.platform.modules.question.model.QuestionDetailView;

import java.time.LocalDateTime;
import java.util.List;

/** 错题视图（含答案解析，但仅限练习产生的错题）。 */
public record WrongQuestionView(
        QuestionDetailView question,
        List<String> lastAnswer,
        LocalDateTime lastAnsweredAt
) {
}
