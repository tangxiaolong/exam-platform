package com.exam.platform.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.exam.platform.common.security.UserContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/**
 * MyBatis-Plus 配置。
 *
 * <p>租户隔离由 {@link TenantLineInnerInterceptor} 在 SQL 层自动追加
 * {@code tenant_id = 当前租户}，避免业务代码遗漏 tenant_id 条件导致的越权读取。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /** 不带 tenant_id 的全局表。 */
    private static final Set<String> TENANT_IGNORED_TABLES = Set.of("sys_tenant");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(@Value("${app.db-type:h2}") String dbType) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(UserContext.tenantId());
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TENANT_IGNORED_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.valueOf(dbType.toUpperCase(Locale.ROOT))));
        return interceptor;
    }
}
