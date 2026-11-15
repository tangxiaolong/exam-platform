package com.exam.platform.modules.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 学习进度：按「用户 + 课时」唯一，支持断点续学。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_progress")
public class CourseProgress extends BaseEntity {

    private Long userId;
    private Long courseId;
    private Long lessonId;
    private Integer progressSeconds;
    private Integer finished;
    private LocalDateTime lastViewedAt;
}
