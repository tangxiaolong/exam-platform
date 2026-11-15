package com.exam.platform.modules.course.dto;

import java.util.List;

/** 管理端新建/编辑课程入参（课时通过独立接口维护）。 */
public record CourseSaveRequest(
        Long projectId,
        Long subjectId,
        String code,
        String title,
        String summary,
        String coverUrl,
        String lecturer,
        Long priceFen,
        String accessType,
        String status,
        Integer sortNo,
        List<LessonSaveRequest> lessons
) {
}
