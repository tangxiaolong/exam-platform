package com.exam.platform.modules.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息发送记录：每次实际投递都留一条，便于排查“为什么没收到”。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_send_log")
public class MsgSendLog extends BaseEntity {

    private Long taskId;
    private Long userId;
    private String channel;
    private String templateCode;
    private String requestJson;
    private String responseJson;
    private Integer success;
    private String errorMsg;
}
