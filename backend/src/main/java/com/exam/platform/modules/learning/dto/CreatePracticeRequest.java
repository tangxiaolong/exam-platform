package com.exam.platform.modules.learning.dto;

/** 创建练习。mode：SEQUENCE / RANDOM / WRONG；knowledgePointId 非空时按知识点（章节练习）取题。 */
public record CreatePracticeRequest(Long subjectId, String mode, Integer limit, Long knowledgePointId) {
}
