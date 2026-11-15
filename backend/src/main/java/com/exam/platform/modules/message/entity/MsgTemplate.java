package com.exam.platform.modules.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息模板：标题与正文可配置，正文里用 {@code {键}} 占位符引用消息 payload。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_template")
public class MsgTemplate extends BaseEntity {

    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    private String templateCode;
    private String title;
    private String content;
    private Integer enabled;
}
