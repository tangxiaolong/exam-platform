package com.exam.platform.modules.course.dto;

/** 管理端维护课时入参。freePreview=true 的课时无需权益即可试看。 */
public record LessonSaveRequest(
        String title,
        String content,
        String videoUrl,
        Integer durationSeconds,
        Boolean freePreview,
        Integer sortNo,
        String status
) {
}
