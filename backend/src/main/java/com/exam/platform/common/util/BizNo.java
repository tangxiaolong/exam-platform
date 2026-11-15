package com.exam.platform.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 业务单号生成：订单号、支付单号、准考证号。 */
public final class BizNo {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private BizNo() {
    }

    public static String orderNo() {
        return prefix() + "OD";
    }

    public static String payNo() {
        return prefix() + "PY";
    }

    public static String ticketNo() {
        return prefix() + "TK";
    }

    public static String refundNo() {
        return prefix() + "RF";
    }

    public static String grantNo() {
        return prefix() + "GR";
    }

    public static String exportNo() {
        return prefix() + "EX";
    }

    public static String reconNo() {
        return prefix() + "RC";
    }

    private static String prefix() {
        return LocalDateTime.now().format(TS) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
