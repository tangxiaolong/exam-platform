package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 考试场次。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_session")
public class ExamSession extends BaseEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long projectId;
    private Long paperId;
    private Long snapshotId;
    private String name;
    private LocalDateTime registerStartAt;
    private LocalDateTime registerEndAt;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationMinutes;
    private Integer allowEnterBeforeMinutes;
    private Integer lateLimitMinutes;
    private LocalDateTime resultPublishAt;
    private Integer quota;
    private String noticeVersion;
    private String noticeContent;
    private String status;
}
