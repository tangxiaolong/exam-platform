package com.exam.platform.modules.report;

import java.math.BigDecimal;

/**
 * 题库使用分析的单题汇总。
 *
 * <p>{@code appearedCount} 是「被抽中的答卷份数」，{@code accuracy} 为答对数占比（1 位小数）。
 * 两个数字必须一起看：出现 2 次、正确率 50% 的题与出现 200 次、正确率 50% 的题，
 * 前者的结论要弱得多。</p>
 */
public record QuestionUsageView(
        Long questionId,
        String stem,
        String type,
        Long subjectId,
        int appearedCount,
        int correctCount,
        BigDecimal accuracy
) {
}
