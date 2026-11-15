package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 成绩版本历史：成绩发布与每次更正都会留一条，用于追溯“谁在什么时候把分数改成了多少”。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_result_history")
public class ExamResultHistory extends BaseEntity {

    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_CORRECT = "CORRECT";

    private Long attemptId;
    private Integer revision;
    private String operation;
    private BigDecimal totalScore;
    private String detailJson;
    private Long operatorId;
    private String remark;
}
