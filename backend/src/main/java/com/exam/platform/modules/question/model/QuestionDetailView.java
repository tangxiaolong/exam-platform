package com.exam.platform.modules.question.model;

import java.util.List;

/** 含答案解析的题目视图：仅管理端、交卷后成绩、答完题后的练习复盘可返回。 */
public record QuestionDetailView(
        Long id,
        Long subjectId,
        String type,
        String stem,
        List<OptionItem> options,
        List<String> answer,
        String analysis,
        String difficulty,
        String tags,
        String status,
        Integer version,
        String source
) {
}
