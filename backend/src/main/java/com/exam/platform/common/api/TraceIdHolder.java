package com.exam.platform.common.api;

import org.slf4j.MDC;

/** traceId 存取（由 {@code TraceIdFilter} 写入 MDC，贯穿日志与响应体）。 */
public final class TraceIdHolder {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIdHolder() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
