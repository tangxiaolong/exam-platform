package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端优惠券：创建 → 考生领取 → 停发后不可领。
 *
 * <p>验证两条路径共用同一套校验：后台创建的券立刻出现在考生可领列表
 * （claim 成功），停发后考生领取被拒——不存在「后台建了却领不了」的分叉。</p>
 */
@DisplayName("管理端优惠券")
class CouponAdminTest extends ApiTestSupport {

    @Test
    @DisplayName("创建 → 考生领取 → 停发后不可领")
    void createClaimThenStop() {
        String admin = adminToken();
        String code = "CP" + System.nanoTime() % 1000000;

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "满50减5");
        body.put("type", "AMOUNT");
        body.put("thresholdFen", 5000);
        body.put("valueFen", 500);
        body.put("totalQuantity", 1);
        body.put("perUserLimit", 1);
        body.put("scopeType", "ALL");
        body.put("validFrom", LocalDateTime.now().minusDays(1).toString());
        body.put("validTo", LocalDateTime.now().plusDays(7).toString());

        var created = data(HttpMethod.POST, "/admin/coupons", admin, body);
        long couponId = created.path("id").asLong();
        assertThat(couponId).as("创建返回主键").isPositive();
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");

        // 考生凭编码领取
        String student = wechatToken("cp-claim-" + code, "券考生" + code);
        var grant = data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null);
        assertThat(grant.path("couponId").asLong()).as("券绑到新创建的那张").isEqualTo(couponId);

        // 停发后，另一名考生的领取被拒
        data(HttpMethod.PUT, "/admin/coupons/" + couponId + "/status?status=STOPPED", admin, null);
        String student2 = wechatToken("cp-claim2-" + code, "券考生2" + code);
        assertThat(errorCode(HttpMethod.POST, "/coupons/" + code + "/claim", student2, null))
                .isEqualTo("COUPON_STOPPED");

        // 管理端按关键词能查到这张券
        var page = data("/admin/coupons?keyword=" + code, admin);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("list").get(0).path("status").asText()).isEqualTo("STOPPED");
    }
}
