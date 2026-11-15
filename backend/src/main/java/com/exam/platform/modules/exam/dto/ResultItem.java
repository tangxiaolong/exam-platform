package com.exam.platform.modules.exam.dto;

import com.exam.platform.modules.question.model.OptionItem;

import java.util.List;

/**
 * 成绩明细中的单题结果（交卷后才返回标准答案与解析）。
 *
 * @param marked 主观题是否已完成人工阅卷
 * @param remark 阅卷意见
 */
public record ResultItem(
        Long questionId,
        String type,
        String stem,
        List<OptionItem> options,
        List<String> standardAnswer,
        List<String> yourAnswer,
        boolean correct,
        Integer score,
        Integer gotScore,
        String analysis,
        String remark,
        boolean marked
) {
}
