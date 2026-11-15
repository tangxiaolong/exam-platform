package com.exam.platform.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback 消息转换器：在日志落盘前对最终消息做敏感信息脱敏。
 *
 * <p>这一层是「兜底」而非「替代」：业务代码里该用
 * {@link SensitiveDataMasker#maskPhone} 之类主动脱敏的仍然要主动做，
 * 这里只保证即便有人直接打了明文，日志里也不会留下完整敏感值。</p>
 *
 * <p>启用方式见 {@code logback-spring.xml}：{@code %maskedMsg}。</p>
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
