package com.exam.platform.modules.learning.dto;

/** 创建练习。mode：SEQUENCE / RANDOM / WRONG。 */
public record CreatePracticeRequest(Long subjectId, String mode, Integer limit) {
}
