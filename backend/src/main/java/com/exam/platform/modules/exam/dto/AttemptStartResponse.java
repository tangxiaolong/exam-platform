package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开始考试 / 恢复考试的返回体：
 * 一切时间以服务端为准，客户端计时仅用于展示。
 */
public record AttemptStartResponse(
        Long attemptId,
        Long sessionId,
        String sessionName,
        String status,
        LocalDateTime serverTime,
        LocalDateTime startedAt,
        LocalDateTime deadlineAt,
        Integer durationMinutes,
        String noticeVersion,
        String noticeContent,
        Integer answerVersion,
        List<ExamQuestionView> questions,
        List<SavedAnswer> answers
) {
}
