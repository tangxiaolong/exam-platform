package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作台近 7 日趋势：返回连续日期序列（含今天在末尾），字段齐全。
 *
 * < * 不造业务数据断言具体数值（三张表的按日聚合由 SQL 完成，脏数据不可控）；
 * 这里验证接口可执行、结构稳定、日期连续——聚合数字的正确性归属报表导出的口径。
 */
@DisplayName("工作台趋势")
class DashboardTrendTest extends ApiTestSupport {

    @Test
    @DisplayName("趋势返回连续 7 日且结构完整")
    void trendsShouldReturnContinuousDays() {
        String admin = adminToken();
        var points = data("/admin/dashboard/trends?days=7", admin);

        assertThat(points.size()).as("7 个点").isEqualTo(7);
        assertThat(points.get(0).path("date").asText()).isEqualTo(LocalDate.now().minusDays(6).toString());
        assertThat(points.get(6).path("date").asText()).as("今天在末尾").isEqualTo(LocalDate.now().toString());
        assertThat(points.get(6).path("revenueFen").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(points.get(6).has("registrations")).isTrue();
        assertThat(points.get(6).has("attempts")).isTrue();
        assertThat(points.get(6).has("paidOrders")).isTrue();
    }
}
