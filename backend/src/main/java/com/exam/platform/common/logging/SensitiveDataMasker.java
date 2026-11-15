package com.exam.platform.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志敏感字段脱敏。
 *
 * <p>验收要求「日志中不出现密码、支付密钥、完整敏感个人信息」。
 * 只靠编码规范保证不住，因此这里做两层：</p>
 * <ol>
 *   <li>主动脱敏工具：需要打日志的敏感值先过这里；</li>
 *   <li>{@link MaskingMessageConverter} 在日志输出的最后一刻再兜一次底，
 *       防止有人直接 {@code log.info("password={}", pwd)}。</li>
 * </ol>
 *
 * <p>脱敏策略保留「可排障」的最小信息：手机号留后 4 位、密钥留前 4 位、其余全遮。</p>
 */
public final class SensitiveDataMasker {

    /** key=value / "key":"value" / key: value 形式，按 key 名脱敏。 */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?)(password|passwd|pwd|oldPassword|newPassword|secret|appSecret|app_secret|"
                    + "accessToken|access_token|refreshToken|refresh_token|token|authorization|"
                    + "sign|signature|privateKey|private_key|sessionKey|session_key|"
                    + "cardNo|card_no|bankCard|bank_card|idCard|id_card|phone|mobile)(\"?)"
                    + "(\\s*[=:]\\s*)(\"?)([^\\s,;&\"'\\]})]+)(\"?)");

    /** 11 位手机号。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    /** 18 位身份证号。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})(\\d{8})(\\d{3}[0-9Xx])(?!\\d)");
    /**
     * 16~19 位银行卡号。
     *
     * <p>前置断言排除 {@code =}、{@code :} 与标识符字符，避免把
     * {@code orderNo=2026061612345678} 这类业务单号误判成卡号；
     * {@code cardNo=...} 这类赋值写法由 {@link #KEY_VALUE} 按 key 名覆盖。</p>
     */
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<![=:\\w.])(\\d{16,19})(?!\\d)");
    /** JWT：三段 base64url，常在异常与调试日志里带出。 */
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    private static final String MASK = "****";

    private SensitiveDataMasker() {
    }

    /** 对整条日志文本脱敏。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskKeyValues(text);
        masked = JWT.matcher(masked).replaceAll("<jwt>");
        masked = ID_CARD.matcher(masked).replaceAll(m -> m.group(1) + "********" + m.group(3));
        masked = BANK_CARD.matcher(masked).replaceAll(m -> {
            String digits = m.group(1);
            return digits.substring(0, 4) + "*".repeat(digits.length() - 8) + digits.substring(digits.length() - 4);
        });
        masked = PHONE.matcher(masked).replaceAll(m -> {
            String phone = m.group(1);
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });
        return masked;
    }

    /** 单个敏感值：保留前 prefixKeep 位，其余打码。 */
    public static String maskValue(String value, int prefixKeep) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int keep = Math.max(0, Math.min(prefixKeep, value.length()));
        if (value.length() <= keep) {
            return MASK;
        }
        return value.substring(0, keep) + MASK;
    }

    /** 手机号脱敏：138****5678。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? null : MASK;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 邮箱脱敏：ab***@example.com。 */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String keep = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return keep + "***" + email.substring(at);
    }

    private static String maskKeyValues(String text) {
        Matcher matcher = KEY_VALUE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(6);
            if (MASK.equals(value) || "<jwt>".equals(value)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String replacement = matcher.group(1) + matcher.group(2) + matcher.group(3)
                    + matcher.group(4) + matcher.group(5)
                    + maskValue(value, resolvePrefixKeep(matcher.group(2)))
                    + matcher.group(7);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 密钥类只露前 4 位，其余 1 位不留；口令类完全不露。 */
    private static int resolvePrefixKeep(String key) {
        String lower = key.toLowerCase();
        if (lower.contains("password") || lower.contains("passwd") || lower.contains("pwd")) {
            return 0;
        }
        if (lower.contains("token") || lower.contains("secret") || lower.contains("sign")
                || lower.contains("key")) {
            return 4;
        }
        return 0;
    }
}
