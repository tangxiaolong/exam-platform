package com.exam.platform.modules.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报名记录，唯一约束 (tenant_id, session_id, user_id)。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_registration")
public class ExamRegistration extends BaseEntity {

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long sessionId;
    private Long projectId;
    private Long userId;
    private String status;
    private String ticketNo;
    private String requestId;
}
