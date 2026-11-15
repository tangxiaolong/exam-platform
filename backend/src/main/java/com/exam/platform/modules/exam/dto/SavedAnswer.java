package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 已保存的答案。 */
public record SavedAnswer(Long questionId, List<String> answer, Integer version) {
}
