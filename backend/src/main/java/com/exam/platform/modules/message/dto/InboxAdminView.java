package com.exam.platform.modules.message.dto;

import java.time.LocalDateTime;

/** 管理端发送历史视图：每条收件记录（含收件人与已读状态）。 */
public record InboxAdminView(
        Long id,
        Long userId,
        String title,
        String content,
        boolean read,
        LocalDateTime createdAt
) {
}
