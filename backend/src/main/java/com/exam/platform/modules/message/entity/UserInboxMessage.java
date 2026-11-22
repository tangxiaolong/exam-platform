package com.exam.platform.modules.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 站内消息：一条消息一行收件人，群发即逐用户落行（已读状态天然按人独立）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_inbox_message")
public class UserInboxMessage extends BaseEntity {

    private Long userId;

    private String title;

    private String content;

    /** 已读标记：0-未读，1-已读。 */
    private Integer readFlag;

    /** 群发批次号：同一次群发落下的收件行共享；历史数据为 NULL，按单行成组。 */
    private String batchNo;
}
