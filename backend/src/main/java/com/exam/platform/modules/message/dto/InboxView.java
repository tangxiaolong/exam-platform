package com.exam.platform.modules.message.dto;

import java.time.LocalDateTime;

/** 站内消息视图：收件箱列表行。 */
public record InboxView(
        Long id,
        String title,
        String content,
        boolean read,
        LocalDateTime createdAt
) {
}
