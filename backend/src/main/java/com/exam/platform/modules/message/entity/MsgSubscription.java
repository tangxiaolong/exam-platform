package com.exam.platform.modules.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订阅授权（微信订阅消息为「一次授权一次发送」，因此按剩余次数计量）。
 * 用户在小程序点击“允许接收通知”后调用上报接口累加次数，发送成功扣减。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_subscription")
public class MsgSubscription extends BaseEntity {

    private Long userId;
    private String templateCode;
    private Integer remaining;
}
