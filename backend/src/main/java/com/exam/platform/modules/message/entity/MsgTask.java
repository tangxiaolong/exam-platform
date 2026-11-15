package com.exam.platform.modules.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 消息任务：业务事件（报名成功、支付成功、成绩发布等）先落任务表，
 * 再由发送器投递，保证“业务成功不依赖消息送达”。
 *
 * <p>幂等键为 (tenant_id, scene, biz_key, user_id)，同一业务事件不会重复创建任务。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_task")
public class MsgTask extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String SCENE_REGISTER = "EXAM_REGISTER";
    public static final String SCENE_PAYMENT = "PAYMENT_SUCCESS";
    public static final String SCENE_RESULT = "RESULT_PUBLISHED";

    private Long userId;
    private String scene;
    private String templateCode;
    private String payloadJson;
    private String bizKey;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime scheduledAt;
    private LocalDateTime sentAt;
    private String errorMsg;
}
