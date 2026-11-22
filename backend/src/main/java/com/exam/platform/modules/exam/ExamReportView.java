package com.exam.platform.modules.exam;

import java.math.BigDecimal;
import java.util.List;

/**
 * 场次成绩分析视图。
 *
 * <p>{@code passPercent} 跟着 {@code passRate} 一起返回：及格与否是相对满分的比例，
 * 前端展示「及格率 75%（≥60%）」时不需要再写死一个数字。</p>
 */
public record ExamReportView(
        int participantCount,
        BigDecimal avgScore,
        BigDecimal maxScore,
        BigDecimal minScore,
        BigDecimal passRate,
        int fullScore,
        int passPercent,
        List<Band> distribution
) {

    /** 分数段：label 用相对满分的百分比区间，count 为人数。 */
    public record Band(String label, int count) {
    }

    /** 逐题正确率：answered 为作答次数，correct 为答对次数，accuracy 为百分比（保留 1 位小数）。 */
    public record QuestionAccuracy(
            Long questionId,
            String stem,
            String type,
            int answered,
            int correct,
            BigDecimal accuracy
    ) {
    }

    /** 排行榜条目：同分并列、后续跳号（1,1,3 式），与其他排名展示习惯一致。 */
    public record LeaderboardEntry(
            int rank,
            Long userId,
            String userName,
            BigDecimal totalScore
    ) {
    }
}
