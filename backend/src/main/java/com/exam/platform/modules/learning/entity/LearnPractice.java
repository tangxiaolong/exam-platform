package com.exam.platform.modules.learning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 练习会话（顺序 / 随机 / 错题 / 收藏）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("learn_practice")
public class LearnPractice extends BaseEntity {

    public static final String MODE_SEQUENCE = "SEQUENCE";
    public static final String MODE_RANDOM = "RANDOM";
    public static final String MODE_WRONG = "WRONG";

    private Long userId;
    private Long subjectId;
    private String mode;
    private String questionIds;
    private Integer totalCount;
    private Integer answeredCount;
    private Integer correctCount;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
