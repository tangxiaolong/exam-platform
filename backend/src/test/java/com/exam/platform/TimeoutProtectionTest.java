package com.exam.platform;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.GlobalExceptionHandler;
import com.exam.platform.support.ApiTestSupport;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时保护验证（WBS 4.5「答题保存接口具备幂等、限流和超时保护」的最后一项）。
 *
 * <p>超时保护不是「配一下就完事」，配置写错是静默失效——MyBatis 的
 * {@code default-statement-timeout} 不生效时不会有任何报错，只会在压测时表现为
 * 「偶尔卡住」。所以这里断言到 MyBatis 运行时配置，而不是断言 yml 里有这行字。</p>
 */
@DisplayName("接口超时保护：SQL 执行上限 + 过载异常归类")
class TimeoutProtectionTest extends ApiTestSupport {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    @DisplayName("MyBatis 默认语句超时已生效，慢查询不会无限占用连接")
    void statementTimeoutShouldBeApplied() {
        Integer timeout = sqlSessionFactory.getConfiguration().getDefaultStatementTimeout();
        assertThat(timeout)
                .as("应为每条 SQL 设置执行上限（秒），否则集中开考时慢查询会拖垮连接池")
                .isNotNull()
                .isBetween(1, 60);
    }

    @Test
    @DisplayName("数据库超时与连接不可用归类为可重试错误码，不当成代码 bug")
    void dbOverloadShouldMapToRetryableCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertCode(handler.handleDbTimeout(new QueryTimeoutException("query timeout")), "COMMON_TIMEOUT");
        assertCode(handler.handleDbTimeout(new CannotAcquireLockException("lock wait timeout")),
                "COMMON_TIMEOUT");
        assertCode(handler.handleDbUnavailable(new CannotGetJdbcConnectionException("pool exhausted")),
                "COMMON_BUSY");
    }

    @Test
    @DisplayName("已配置的 SQL 超时不阻断正常业务读")
    void normalQueryShouldStillWork() {
        String admin = adminToken();
        assertThat(data("/projects?pageNo=1&pageSize=5", admin).path("list").size()).isPositive();
    }

    private void assertCode(Result<Void> result, String expected) {
        assertThat(result.getCode()).isEqualTo(expected);
        assertThat(result.getMessage()).isNotBlank();
    }
}
