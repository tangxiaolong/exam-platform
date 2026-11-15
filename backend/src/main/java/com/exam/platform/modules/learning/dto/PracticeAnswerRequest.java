package com.exam.platform.modules.learning.dto;

import java.util.List;

/** 提交练习答案。 */
public record PracticeAnswerRequest(List<String> answer) {
}
