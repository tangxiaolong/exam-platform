package com.exam.platform.modules.paper.model;

/** 试卷中的题目与分值配置，持久化为 exam_paper.question_json。 */
public record PaperQuestionItem(Long questionId, Integer score) {
}
