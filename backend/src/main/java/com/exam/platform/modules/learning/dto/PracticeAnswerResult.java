package com.exam.platform.modules.learning.dto;

import java.util.List;

/** 练习判分结果：练习场景下即时返回标准答案与解析。 */
public record PracticeAnswerResult(
        boolean correct,
        List<String> standardAnswer,
        String analysis,
        Integer answeredCount,
        Integer correctCount
) {
}
