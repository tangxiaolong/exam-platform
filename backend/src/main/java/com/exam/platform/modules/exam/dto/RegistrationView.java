package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;

/** 报名结果 / 准考信息。 */
public record RegistrationView(
        Long registrationId,
        Long sessionId,
        String sessionName,
        String status,
        String ticketNo,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer durationMinutes,
        String noticeVersion,
        String noticeContent
) {
}
