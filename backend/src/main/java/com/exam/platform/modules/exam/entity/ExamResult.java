package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 判分结果，一次 attempt 只允许一条。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_result")
public class ExamResult extends BaseEntity {

    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_GRADED = "GRADED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private Long attemptId;
    private Long sessionId;
    private Long userId;
    private BigDecimal totalScore;
    private Integer fullScore;
    private Integer correctCount;
    private Integer questionCount;
    private Integer scoringVersion;
    private String detailJson;
    private String status;
    private LocalDateTime publishedAt;
    /** 成绩版本号：发布为 1，每次更正 +1 */
    private Integer revision;
}
