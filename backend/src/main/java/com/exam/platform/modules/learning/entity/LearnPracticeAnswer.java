package com.exam.platform.modules.learning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 练习作答明细，错题列表即 correct = 0 的记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("learn_practice_answer")
public class LearnPracticeAnswer extends BaseEntity {

    private Long practiceId;
    private Long userId;
    private Long questionId;
    private Long subjectId;
    private String answerJson;
    private Integer correct;
}
