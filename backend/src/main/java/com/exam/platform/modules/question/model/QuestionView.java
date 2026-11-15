package com.exam.platform.modules.question.model;

import java.util.List;

/** 对外题目视图：不含标准答案与解析（练习/考试过程中使用）。 */
public record QuestionView(
        Long id,
        Long subjectId,
        String type,
        String stem,
        List<OptionItem> options,
        String difficulty,
        String tags
) {
}
