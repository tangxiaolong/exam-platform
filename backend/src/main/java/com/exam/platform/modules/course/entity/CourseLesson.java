package com.exam.platform.modules.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 课时。freePreview=1 的课时无需权益即可试看。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_lesson")
public class CourseLesson extends BaseEntity {

    private Long courseId;
    private String title;
    private String content;
    private String videoUrl;
    private Integer durationSeconds;
    private Integer freePreview;
    private Integer sortNo;
    /** ENABLED / DISABLED */
    private String status;
}
