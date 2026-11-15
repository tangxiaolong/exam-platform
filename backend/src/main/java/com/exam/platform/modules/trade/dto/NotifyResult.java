package com.exam.platform.modules.trade.dto;

/** 支付通知处理结果，直接映射为对支付平台的应答。 */
public record NotifyResult(boolean success, String code, String message) {

    public static NotifyResult ok() {
        return new NotifyResult(true, "0", "SUCCESS");
    }

    public static NotifyResult fail(String code, String message) {
        return new NotifyResult(false, code, message);
    }
}
