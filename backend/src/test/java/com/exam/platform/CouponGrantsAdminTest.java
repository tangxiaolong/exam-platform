package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端领取记录：运营能看见这张券被谁领了、当前什么状态。
 */
@DisplayName("优惠券领取记录")
class CouponGrantsAdminTest extends ApiTestSupport {

    @Test
    @DisplayName("考生领券后管理端可见领取记录")
    void grantsShouldShowClaimer() {
        String admin = adminToken();
        String code = "GR" + System.nanoTime() % 1000000;

        var coupon = data(HttpMethod.POST, "/admin/coupons", admin, Map.of(
                "code", code,
                "name", "领取记录测试券",
                "type", "AMOUNT",
                "thresholdFen", 100,
                "valueFen", 100,
                "totalQuantity", 10,
                "perUserLimit", 1,
                "scopeType", "ALL",
                "validFrom", "2026-01-01T00:00:00",
                "validTo", "2026-12-31T00:00:00"));
        long couponId = coupon.path("id").asLong();

        String student = wechatToken("coupon-grants-student", "领券记录考生");
        data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null);

        var grants = data("/admin/coupons/" + couponId + "/grants?pageNo=1&pageSize=10", admin);
        assertThat(grants.path("total").asLong()).as("至少一条领取记录").isGreaterThanOrEqualTo(1);

        var first = grants.path("list").get(0);
        assertThat(first.path("status").asText()).as("刚领的券是未使用").isEqualTo("UNUSED");
        assertThat(first.path("username").asText()).as("能定位到持券人").isNotBlank();
        assertThat(first.path("userId").asLong()).isPositive();
        assertThat(first.has("grantedAt")).isTrue();
    }
}
