package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 考生答案，唯一维度 (tenant_id, attempt_id, question_id)。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_answer")
public class ExamAnswer extends BaseEntity {

    private Long attemptId;
    private Long questionId;
    private String answerJson;
    private Integer answerVersion;
    private Integer isMarked;
    private LocalDateTime savedAt;
}
