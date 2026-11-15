package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 主观题人工阅卷记录：一次作答的每道主观题一条。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_marking")
public class ExamMarking extends BaseEntity {

    private Long attemptId;
    private Long questionId;
    /** 人工给出的得分 */
    private Integer score;
    private Integer fullScore;
    private String remark;
    private Long markerId;
    private LocalDateTime markedAt;
}
