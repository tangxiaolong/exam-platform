package com.exam.platform.modules.question.support;

import com.exam.platform.modules.question.entity.QbQuestion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客观题判分规则（练习与正式考试共用）。
 *
 * <p>首期规则：单选/判断必须完全一致；多选默认“全对才得分”，
 * 通过 {@code app.exam.multiple-partial-score} 可切换为漏选按比例给分（P1）。</p>
 */
public final class AnswerJudge {

    private AnswerJudge() {
    }

    public static boolean isCorrect(List<String> standard, List<String> given) {
        Set<String> a = normalize(standard);
        Set<String> b = normalize(given);
        return !a.isEmpty() && a.equals(b);
    }

    private static Set<String> normalize(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean isObjective(String type) {
        return QbQuestion.TYPE_SINGLE.equals(type)
                || QbQuestion.TYPE_MULTIPLE.equals(type)
                || QbQuestion.TYPE_JUDGE.equals(type);
    }
}
