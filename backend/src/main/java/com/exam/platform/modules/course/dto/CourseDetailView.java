package com.exam.platform.modules.course.dto;

import java.util.List;

/**
 * 课程详情视图。
 *
 * @param accessible 当前用户是否可观看付费课时（免费课程或持有 COURSE 权益）
 */
public record CourseDetailView(
        Long id,
        String code,
        String title,
        String summary,
        String coverUrl,
        String lecturer,
        Long projectId,
        Long subjectId,
        Long priceFen,
        String accessType,
        String status,
        boolean accessible,
        int lessonCount,
        int finishedLessonCount,
        List<LessonView> lessons
) {
}
