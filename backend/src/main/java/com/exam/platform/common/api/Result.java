package com.exam.platform.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 统一响应体，遵循文档约定的 {@code {code, message, data, traceId}} 结构。
 * 成功时 code 固定为 "0"，失败时为业务错误码（AUTH_/EXAM_/ORDER_ 等前缀）。
 */
@Data
public class Result<T> {

    public static final String SUCCESS_CODE = "0";

    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = SUCCESS_CODE;
        result.message = "成功";
        result.data = data;
        result.traceId = TraceIdHolder.current();
        return result;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        result.traceId = TraceIdHolder.current();
        return result;
    }

    /** 内部判断用，不参与序列化，保持响应体与文档约定的四个字段一致。 */
    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
