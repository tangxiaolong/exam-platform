package com.exam.platform.modules.paper.model;

import com.exam.platform.modules.question.model.OptionItem;

import java.util.List;

/** 快照中的单题：题面、选项、标准答案、分值在快照生成后不可变。 */
public record SnapshotQuestion(
        Long questionId,
        Long subjectId,
        String type,
        String stem,
        List<OptionItem> options,
        List<String> answer,
        String analysis,
        Integer score,
        String difficulty
) {
}
