package com.exam.platform.modules.exam.dto;

import java.util.List;

/** 阅卷视图：主观题题干、参考答案、考生作答与当前得分。 */
public record MarkingItemView(
        Long questionId,
        String type,
        String stem,
        List<String> referenceAnswer,
        List<String> givenAnswer,
        Integer fullScore,
        Integer score,
        String remark,
        boolean marked
) {
}
