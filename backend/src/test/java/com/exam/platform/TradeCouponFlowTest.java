package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 优惠券 + 部分退款。
 *
 * <p>这两块合在一个测试类里是因为它们的关键路径互相咬合：券决定了实付金额，
 * 实付金额决定了部分退款能退多少。只测其中一半，另一半的算法错误就看不出来。</p>
 *
 * <p>券模板用 JDBC 直接插入（按商业规则由运营配置，不走业务接口），
 * 这样测试不依赖「后台建券」这个尚未实现的功能。</p>
 */
@DisplayName("优惠券与部分退款")
class TradeCouponFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("领券受人均限制约束，领完即止")
    void claimShouldRespectPerUserLimit() {
        String student = wechatToken("coupon-claim-student", "领券考生");
        String code = insertCoupon("AMOUNT", 0, 1000, 0, null, 1, 1);

        JsonNode grant = data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null);
        assertThat(grant.path("status").asText()).isEqualTo("UNUSED");

        // 同一人再领：被人均限制挡住，而不是静默多发一张
        assertThat(errorCode(HttpMethod.POST, "/coupons/" + code + "/claim", student, null))
                .isEqualTo("COUPON_LIMIT_REACHED");

        // 已领完的券不再出现在可领列表
        JsonNode claimable = data("/coupons/claimable", student);
        for (JsonNode coupon : claimable) {
            assertThat(coupon.path("code").asText()).isNotEqualTo(code);
        }
    }

    @Test
    @DisplayName("用券下单：抵扣由服务端计算，支付成功后核销")
    void couponShouldDiscountOrderAndBeConsumed() {
        String student = wechatToken("coupon-pay-student", "用券支付考生");
        JsonNode product = firstOnSaleProduct(student);
        long price = product.path("priceFen").asLong();
        String code = insertCoupon("AMOUNT", 0, 1000, 0, null, 1, 10);
        long grantId = data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null).path("id").asLong();

        JsonNode order = data(HttpMethod.POST, "/orders", student, Map.of(
                "productId", product.path("id").asLong(),
                "quantity", 1,
                // 客户端传的金额必须被忽略
                "payAmountFen", 1,
                "couponGrantId", grantId));

        assertThat(order.path("totalAmountFen").asLong()).isEqualTo(price);
        assertThat(order.path("discountAmountFen").asLong()).isEqualTo(1000);
        assertThat(order.path("payAmountFen").asLong()).isEqualTo(price - 1000);

        // 下单只锁定，未支付不能核销
        assertThat(data("/coupons/mine?status=LOCKED", student).size()).isEqualTo(1);

        JsonNode payment = data(HttpMethod.POST, "/orders/" + order.path("id").asLong() + "/payments", student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        assertThat(data("/orders/" + order.path("id").asLong(), student).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data("/coupons/mine?status=USED", student).size()).isEqualTo(1);
        assertThat(data("/coupons/mine?status=LOCKED", student).size()).isEqualTo(0);
    }

    @Test
    @DisplayName("门槛不足与超额抵扣都被拒，不能产生 1 分钱订单")
    void couponShouldRespectThresholdAndMinimumPay() {
        String student = wechatToken("coupon-threshold-student", "门槛考生");
        JsonNode product = firstOnSaleProduct(student);
        long price = product.path("priceFen").asLong();

        // 门槛设为商品价 + 1 分：必然不满足
        String code = insertCoupon("AMOUNT", price + 1, 100, 0, null, 1, 10);
        long grantId = data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null).path("id").asLong();
        assertThat(errorCode(HttpMethod.POST, "/orders", student, Map.of(
                "productId", product.path("id").asLong(), "quantity", 1, "couponGrantId", grantId)))
                .isEqualTo("COUPON_THRESHOLD_NOT_MET");

        // 抵扣额等于订单金额：会算出 0 元单，必须拒绝
        String fullCode = insertCoupon("AMOUNT", 0, price, 0, null, 1, 10);
        long fullGrant = data(HttpMethod.POST, "/coupons/" + fullCode + "/claim", student, null).path("id").asLong();
        assertThat(errorCode(HttpMethod.POST, "/orders", student, Map.of(
                "productId", product.path("id").asLong(), "quantity", 1, "couponGrantId", fullGrant)))
                .isEqualTo("COUPON_AMOUNT_INVALID");
    }

    @Test
    @DisplayName("部分退款按实付比例折算，全退完才回收权益")
    void partialRefundShouldProportionallyRefundAndRevokeOnCompletion() {
        String student = wechatToken("coupon-refund-student", "部分退款考生");
        String admin = adminToken();
        JsonNode product = firstOnSaleProduct(student);
        long price = product.path("priceFen").asLong();
        String code = insertCoupon("AMOUNT", 0, 1000, 0, null, 1, 10);
        long grantId = data(HttpMethod.POST, "/coupons/" + code + "/claim", student, null).path("id").asLong();

        JsonNode order = data(HttpMethod.POST, "/orders", student, Map.of(
                "productId", product.path("id").asLong(), "quantity", 2, "couponGrantId", grantId));
        long orderId = order.path("id").asLong();
        long payAmount = order.path("payAmountFen").asLong();
        JsonNode payment = data(HttpMethod.POST, "/orders/" + orderId + "/payments", student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        JsonNode items = data("/orders/" + orderId + "/items", student);
        assertThat(items.size()).isEqualTo(1);
        long itemId = items.get(0).path("id").asLong();

        // 退 1 件：明细实付 = (原价×2 - 1000)，按数量切半
        JsonNode first = data(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "退一件", "orderItemId", itemId, "quantity", 1));
        assertThat(first.path("amountFen").asLong())
                .as("order=%s items=%s", order, items)
                .isEqualTo(payAmount / 2);

        data(HttpMethod.POST, "/admin/refunds/" + first.path("id").asLong() + "/approve?remark=ok", admin, null);
        assertThat(data("/orders/" + orderId, student).path("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        // 部分退款保留权益：按比例回收会产生「退掉的是哪几份」的争议
        assertThat(data("/entitlements", student).get(0).path("status").asText()).isEqualTo("AVAILABLE");

        // 再退剩余 1 件：累计达到实付，订单转 REFUNDED 并回收权益
        JsonNode second = data(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "退剩余一件", "orderItemId", itemId, "quantity", 1));
        assertThat(second.path("amountFen").asLong()).isEqualTo(payAmount - payAmount / 2);
        data(HttpMethod.POST, "/admin/refunds/" + second.path("id").asLong() + "/approve?remark=ok", admin, null);

        assertThat(data("/orders/" + orderId, student).path("status").asText()).isEqualTo("REFUNDED");
        assertThat(data("/entitlements", student).get(0).path("status").asText()).isEqualTo("REVOKED");

        // 已全退，再申请必须无金额可退
        assertThat(errorCode(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "再退", "orderItemId", itemId, "quantity", 1)))
                .isEqualTo("REFUND_NOTHING");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 插一张券模板并返回 code。
     *
     * @param type            AMOUNT / DISCOUNT
     * @param thresholdFen    门槛（分）
     * @param valueFen        满减额（分），AMOUNT 用
     * @param discountPercent 折扣百分比，DISCOUNT 用（85 = 85 折）
     * @param maxDiscountFen  折扣封顶，可为 null
     * @param totalQuantity   发行量，0 = 不限
     * @param perUserLimit    人均限领
     */
    private String insertCoupon(String type, long thresholdFen, long valueFen, int discountPercent,
                                Long maxDiscountFen, int totalQuantity, int perUserLimit) {
        String code = "TEST-" + type + "-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> values = new HashMap<>();
        values.put("code", code);
        jdbcTemplate.update("""
                        INSERT INTO marketing_coupon (tenant_id, code, name, type, threshold_fen, value_fen,
                            discount_percent, max_discount_fen, total_quantity, per_user_limit, scope_type, scope_id,
                            valid_from, valid_to, status, claimed_quantity, created_at, updated_at, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ALL', NULL, ?, ?, 'ACTIVE', 0, ?, ?, 0)
                        """,
                1L, code, "测试券-" + code, type, thresholdFen, valueFen, discountPercent, maxDiscountFen,
                totalQuantity, perUserLimit,
                Timestamp.valueOf(now.minusDays(1)), Timestamp.valueOf(now.plusDays(30)),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        return code;
    }

    private JsonNode firstOnSaleProduct(String token) {
        JsonNode page = data("/products?pageNo=1&pageSize=10", token);
        assertThat(page.path("list").size()).as("应存在上架商品").isGreaterThan(0);
        return page.path("list").get(0);
    }
}
