package com.exam.platform.modules.paper.model;

import java.util.List;

/** 试卷快照内容。 */
public record SnapshotContent(
        Long paperId,
        Integer paperVersion,
        String paperName,
        Integer totalScore,
        List<SnapshotQuestion> questions
) {
}
