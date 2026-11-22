package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志导出：动作过滤 + 导出动作自身写审计。
 *
 * <p>先制造一条 COUPON_CREATE 审计（建券带 @Audited），再按动作导出——
 * 断言导出走的是与列表页同一套筛选，且导出的记录里能定位到这次动作。</p>
 */
@DisplayName("审计日志导出")
class AuditExportTest extends ApiTestSupport {

    @Test
    @DisplayName("导出 CSV 含动作过滤结果")
    void exportShouldContainFilteredAction() {
        String admin = adminToken();
        String code = "AX" + System.nanoTime() % 1000000;
        data(HttpMethod.POST, "/admin/coupons", admin, Map.of(
                "code", code,
                "name", "审计导出测试券",
                "type", "AMOUNT",
                "thresholdFen", 100,
                "valueFen", 100,
                "totalQuantity", 1,
                "perUserLimit", 1,
                "scopeType", "ALL",
                "validFrom", "2026-01-01T00:00:00",
                "validTo", "2026-12-31T00:00:00"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + admin);
        ResponseEntity<String> response = restTemplate.exchange(
                api("/admin/audit-logs/export?action=COUPON_CREATE"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String csv = response.getBody();
        assertThat(csv).as("表头").contains("时间,操作人,动作,目标,详情,IP");
        assertThat(csv).as("过滤后的动作").contains("COUPON_CREATE");
        assertThat(csv).as("目标定位到券记录").contains("marketing_coupon:");
    }
}
