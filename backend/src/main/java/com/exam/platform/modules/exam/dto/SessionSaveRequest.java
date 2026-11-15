package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;

/** 管理端新建/编辑考试场次入参。 */
public record SessionSaveRequest(
        Long projectId,
        Long paperId,
        String name,
        LocalDateTime registerStartAt,
        LocalDateTime registerEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer durationMinutes,
        Integer allowEnterBeforeMinutes,
        Integer lateLimitMinutes,
        LocalDateTime resultPublishAt,
        Integer quota,
        String noticeVersion,
        String noticeContent
) {
}
