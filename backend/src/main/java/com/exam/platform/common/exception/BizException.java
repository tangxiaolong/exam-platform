package com.exam.platform.common.exception;

import lombok.Getter;

/** 业务异常：携带文档约定的错误码前缀（AUTH_/TENANT_/EXAM_/QUESTION_/ORDER_/PAY_/ENTITLEMENT_）。 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String code, String message) {
        return new BizException(code, message);
    }

    public static BizException notFound(String what) {
        return new BizException("COMMON_NOT_FOUND", what + "不存在");
    }

    public static BizException badRequest(String message) {
        return new BizException("COMMON_BAD_REQUEST", message);
    }

    public static BizException forbidden(String message) {
        return new BizException("AUTH_FORBIDDEN", message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(code, message);
    }
}
