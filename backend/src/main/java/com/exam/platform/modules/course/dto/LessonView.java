package com.exam.platform.modules.course.dto;

/** 课时视图：未解锁时不下发 videoUrl 与正文。 */
public record LessonView(
        Long id,
        String title,
        Integer durationSeconds,
        boolean freePreview,
        Integer sortNo,
        String status,
        boolean locked,
        String videoUrl,
        String content,
        Integer progressSeconds,
        boolean finished
) {
}
