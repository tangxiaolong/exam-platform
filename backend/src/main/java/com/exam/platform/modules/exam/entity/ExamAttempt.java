package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 考试作答实例。状态机：CREATED → IN_PROGRESS → SUBMITTED / EXPIRED → GRADED。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_attempt")
public class ExamAttempt extends BaseEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_GRADED = "GRADED";

    public static final String REASON_USER = "USER_SUBMIT";
    public static final String REASON_AUTO = "AUTO_SUBMIT";

    private Long sessionId;
    private Long registrationId;
    private Long userId;
    private Long paperSnapshotId;
    private Integer attemptNo;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime submittedAt;
    private String submitReason;
    private BigDecimal totalScore;
    private Integer scoringVersion;
    private Integer answerVersion;
}
