package com.exam.platform.modules.question.model;

import java.util.List;

/** 批量移动科目入参：一次最多 200 条，与批量启停的上限一致。 */
public record QuestionBatchSubjectRequest(List<Long> ids, Long subjectId) {
}
