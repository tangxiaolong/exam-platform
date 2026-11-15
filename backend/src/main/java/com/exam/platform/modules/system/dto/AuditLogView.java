package com.exam.platform.modules.system.dto;

import java.time.LocalDateTime;

/**
 * 审计日志视图。
 *
 * <p>只返回排查所需字段：谁（username/userId）、做了什么（action）、对什么做的（target）、
 * 结果摘要（detail）、从哪来（ip）、什么时候。审计的价值就在于事后能还原这三件事，
 * 所以这里不做任何脱敏折叠（detail 里已经是操作摘要，不含报文原文）。</p>
 */
public record AuditLogView(
        Long id,
        Long userId,
        String username,
        String action,
        String target,
        String detail,
        String ip,
        LocalDateTime createdAt
) {
}
