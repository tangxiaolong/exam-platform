package com.exam.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 综合考试平台后台服务。
 *
 * <p>采用“模块化单体”形态：按 {@code com.exam.platform.modules.*} 做领域分包，
 * 各模块之间只通过 Service 协作，不跨模块直接操作对方的表。
 * 后续若某个模块（考试引擎、交易支付）需要独立部署，可整体拆出为独立服务。</p>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.exam.platform.modules.**.mapper")
public class ExamPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamPlatformApplication.class, args);
    }
}
