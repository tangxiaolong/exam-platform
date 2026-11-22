package com.exam.platform.modules.question.model;

import java.util.List;

/**
 * 批量启停题目请求：ids 一次上限 200 条（校验在 QuestionService）。
 */
public record QuestionBatchStatusRequest(List<Long> ids, String status) {
}
