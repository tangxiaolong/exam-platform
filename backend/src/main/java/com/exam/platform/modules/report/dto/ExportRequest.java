package com.exam.platform.modules.report.dto;

/**
 * 导出请求参数。
 *
 * <p>各报表通用的时间区间 + 可选过滤条件，未知字段按报表类型忽略。</p>
 *
 * @param type        报表类型：ORDER / RESULT / REGISTRATION
 * @param startAt     起始时间（含），可空
 * @param endAt       结束时间（含），可空
 * @param status      状态过滤，可空
 * @param sessionId   场次过滤（成绩/报名报表用），可空
 */
public record ExportRequest(
        String type,
        String startAt,
        String endAt,
        String status,
        Long sessionId
) {
}
