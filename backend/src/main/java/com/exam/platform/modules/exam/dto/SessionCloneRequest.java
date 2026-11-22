package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;

/**
 * 复制场次的入参：只需新的名称与时间窗。
 *
 * <p>项目、试卷、时长、迟到限制、名额、须知等规则全部沿用源场次——
 * 「复制」的含义就是沿用规则，而不是重新填一遍表单。</p>
 */
public record SessionCloneRequest(
        String name,
        LocalDateTime registerStartAt,
        LocalDateTime registerEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime resultPublishAt
) {
}
