package com.exam.platform.common.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 全局日期序列化统一为「年月日时分秒」：
 * <ul>
 *   <li>LocalDateTime / java.util.Date / Timestamp → {@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>LocalDate → {@code yyyy-MM-dd}，LocalTime → {@code HH:mm:ss}</li>
 *   <li>统一东八区，避免部署时区不同导致时间漂移</li>
 * </ul>
 * 前端各页无需再自行截取/替换 ISO 串，直接展示接口返回值即可。
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer shanghaiDateTimeCustomizer() {
        DateTimeFormatter dateTime = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter date = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter time = DateTimeFormatter.ofPattern(TIME_PATTERN);
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_TIME_PATTERN);
        return builder -> {
            builder.serializers(new LocalDateTimeSerializer(dateTime),
                    new LocalDateSerializer(date),
                    new LocalTimeSerializer(time));
            // 反序列化保持 Spring 默认（ISO-8601 宽容解析），仅统一输出格式
            // 覆盖 java.util.Date / java.sql.Timestamp 的默认输出
            builder.dateFormat(dateFormat);
            builder.timeZone(TimeZone.getTimeZone("GMT+8"));
        };
    }
}
