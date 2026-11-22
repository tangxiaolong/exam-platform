package com.exam.platform.modules.learning.dto;

import com.exam.platform.modules.question.model.QuestionDetailView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 错题视图（含答案解析）。
 *
 * <p>{@code source} 区分来源：{@code PRACTICE}-练习答错，{@code EXAM}-考试答错。
 * 合并两源是刻意的——考生心里的「我错过的题」只有一个列表，
 * 分成两个入口只会让人两边都翻不到。</p>
 */
/**
 * 错题条目。
 *
 * <p>{@code subjectName} 是给前端筛选用的：题干里的 {@code subjectId} 是个数字，
 * 单独传下去界面上只能显示「科目 3」，管理员和考生都看不懂。科目名放在这里，
 * 前端就不必再按 projectId 二次查询。</p>
 */
public record WrongQuestionView(
        QuestionDetailView question,
        List<String> lastAnswer,
        LocalDateTime lastAnsweredAt,
        String source,
        String subjectName
) {
    public static final String SOURCE_PRACTICE = "PRACTICE";
    public static final String SOURCE_EXAM = "EXAM";
}
