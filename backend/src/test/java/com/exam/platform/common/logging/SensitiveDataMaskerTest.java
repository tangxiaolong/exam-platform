package com.exam.platform.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 日志敏感字段脱敏断言：一旦日志里出现完整口令/密钥/手机号/身份证/JWT，本用例即失败。 */
@DisplayName("日志脱敏：口令 / 密钥 / 个人信息不落明文")
class SensitiveDataMaskerTest {

    private static final String RAW_PASSWORD = "P@ssw0rd-Very-Secret";
    private static final String RAW_PHONE = "13812345678";
    private static final String RAW_ID_CARD = "310101199001011234";
    private static final String RAW_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

    @Test
    @DisplayName("按 key 名脱敏的字段都取不到明文")
    void shouldMaskSensitiveKeys() {
        String line = "login password=" + RAW_PASSWORD
                + " appSecret=wx-demo-secret-value"
                + " accessToken=abcdefghijklmn"
                + " sign=deadbeefcafe"
                + " \"refresh_token\":\"r3fr3sh-t0ken-v4lu3\"";
        String masked = SensitiveDataMasker.mask(line);

        assertThat(masked).doesNotContain(RAW_PASSWORD);
        assertThat(masked).doesNotContain("wx-demo-secret-value");
        assertThat(masked).doesNotContain("abcdefghijklmn");
        assertThat(masked).doesNotContain("deadbeefcafe");
        assertThat(masked).doesNotContain("r3fr3sh-t0ken-v4lu3");
        assertThat(masked).contains("password=****");
    }

    @Test
    @DisplayName("手机号 / 身份证 / 银行卡只保留可排障片段")
    void shouldMaskPersonalData() {
        String masked = SensitiveDataMasker.mask(
                "用户手机号 " + RAW_PHONE + "，证件 " + RAW_ID_CARD + "，卡号 6222021234567890123");
        assertThat(masked).doesNotContain(RAW_PHONE);
        assertThat(masked).doesNotContain(RAW_ID_CARD);
        assertThat(masked).doesNotContain("6222021234567890123");
        assertThat(masked).contains("138****5678");
    }

    @Test
    @DisplayName("JWT 整段被替换")
    void shouldMaskJwt() {
        String masked = SensitiveDataMasker.mask("Authorization: Bearer " + RAW_JWT);
        assertThat(masked).doesNotContain(RAW_JWT);
        assertThat(masked).contains("<jwt>");
    }

    @Test
    @DisplayName("工具方法保留最小可排障信息")
    void shouldMaskByHelpers() {
        assertThat(SensitiveDataMasker.maskPhone(RAW_PHONE)).isEqualTo("138****5678");
        assertThat(SensitiveDataMasker.maskEmail("zhangsan@example.com")).isEqualTo("zh***@example.com");
        assertThat(SensitiveDataMasker.maskValue("supersecret", 4)).isEqualTo("supe****");
        assertThat(SensitiveDataMasker.maskValue("supersecret", 0)).isEqualTo("****");
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }

    @Test
    @DisplayName("常规日志不被误伤")
    void shouldNotMangleNormalLog() {
        String normal = "订单创建成功 orderNo=2026061612345678 amountFen=9900 traceId=abc123";
        assertThat(SensitiveDataMasker.mask(normal)).isEqualTo(normal);
    }

    // ------------------------------------------------------------------ Logback 兜底

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger("com.exam.platform.sensitive.probe");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("兜底转换器：即便开发忘记主动脱敏，落盘日志也不含明文")
    void converterShouldMaskFormattedEvent() {
        logger.info("登录失败 username=admin password={} phone={}", RAW_PASSWORD, RAW_PHONE);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();
        MaskingMessageConverter converter = new MaskingMessageConverter();
        String rendered = converter.convert(events.get(0));
        assertThat(rendered).doesNotContain(RAW_PASSWORD);
        assertThat(rendered).doesNotContain(RAW_PHONE);
        assertThat(rendered).contains("登录失败");
    }
}
