package com.exam.platform.modules.paper.model;

import java.util.List;

/**
 * 管理端新建/编辑试卷入参。
 *
 * <ul>
 *   <li>FIXED（固定组卷）：使用 {@code questions}；</li>
 *   <li>RULE（规则组卷）：使用 {@code rules}，发布时按规则随机抽题。</li>
 * </ul>
 */
public record PaperSaveRequest(
        Long projectId,
        Long subjectId,
        String name,
        String mode,
        List<PaperQuestionItem> questions,
        List<PaperRuleItem> rules
) {
}
