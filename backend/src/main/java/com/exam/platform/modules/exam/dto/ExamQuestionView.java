package com.exam.platform.modules.exam.dto;

import com.exam.platform.modules.question.model.OptionItem;

/** 考试用题目视图：含分值，绝不含标准答案与解析。 */
public record ExamQuestionView(
        Long questionId,
        String type,
        String stem,
        java.util.List<OptionItem> options,
        Integer score,
        String difficulty
) {
}
