package com.exam.platform.support;

import com.exam.platform.modules.demo.DemoDataInitializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 所有接口级测试的公共基类：<b>每个测试方法前重建数据库</b>。
 *
 * <p>为什么必须这么做：H2 连接串是 {@code jdbc:h2:mem:exam_platform} 且带 {@code DB_CLOSE_DELAY=-1}，
 * 同一个 JVM 里被缓存的多个 Spring 上下文共用同一个库——上一个测试类造的用户、订单、
 * 对账报告会原样留给下一个类。于是「断言总数」「断言列表第一条」这类用例能不能绿，
 * 取决于测试执行顺序：单独跑绿、全套跑红，或者反过来。</p>
 *
 * <p>这里选择「每方法重建」而不是「每类独立库」：后者要为每个测试类新建 Spring 上下文
 * （Tomcat + 连接池），26 个类会既慢又占内存；重建库只需重新执行 Flyway 迁移与演示数据初始化，
 * 单次在 H2 上是几十毫秒级，换来的是用例之间没有任何隐藏依赖。</p>
 *
 * <p>代价是「跨方法共享数据」的写法不再被纵容——这正是要消除的耦合。</p>
 */
public abstract class AbstractApiTestSupport {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    protected Flyway flyway;

    @Autowired(required = false)
    protected DemoDataInitializer demoDataInitializer;

    @BeforeEach
    void rebuildDatabase() {
        // DROP ALL OBJECTS 连 flyway_schema_history 一起清掉，migrate 会从头执行 V1..Vn，
        // 演示数据由初始化器按「库里没有才写」的幂等规则重新落一遍
        jdbcTemplate.execute("DROP ALL OBJECTS");
        if (flyway != null) {
            flyway.migrate();
        }
        if (demoDataInitializer != null) {
            demoDataInitializer.run(new DefaultApplicationArguments(new String[0]));
        }
    }
}
