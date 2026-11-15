package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("退款闭环：申请 → 审核 → 退款 → 权益回收")
class RefundFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("审核通过后订单退款、剩余权益作废，重复审核不重复回收")
    void approveShouldRefundAndRevokeEntitlement() {
        String student = wechatToken("refund-approve-student", "退款测试考生");
        String admin = adminToken();
        JsonNode product = firstOnSaleProduct(student);
        long orderId = paidOrder(student, product, 2);

        // 先用掉 1 次权益 → PART_USED
        JsonNode account = data("/entitlements", student).get(0);
        long accountId = account.path("id").asLong();
        JsonNode consumed = data(HttpMethod.POST,
                "/entitlements/" + accountId + "/consume?amount=1&requestId=refund-consume-1", student, null);
        assertThat(consumed.path("usedAmount").asInt()).isEqualTo(1);
        assertThat(consumed.path("status").asText()).isEqualTo("PART_USED");

        // 同一 requestId 重复核销 → 幂等，不再扣减
        JsonNode consumedAgain = data(HttpMethod.POST,
                "/entitlements/" + accountId + "/consume?amount=1&requestId=refund-consume-1", student, null);
        assertThat(consumedAgain.path("usedAmount").asInt()).isEqualTo(1);

        // 申请退款
        JsonNode refund = data(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "买错了"));
        long refundId = refund.path("id").asLong();
        assertThat(refund.path("status").asText()).isEqualTo("APPLIED");
        assertThat(refund.path("amountFen").asLong()).isEqualTo(product.path("priceFen").asLong() * 2);

        // 同一订单重复申请 → 拒绝
        String duplicate = errorCode(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "再申请一次"));
        assertThat(duplicate).isEqualTo("REFUND_EXISTS");

        // 审核通过 → 订单退款、剩余权益作废
        JsonNode approved = data(HttpMethod.POST, "/admin/refunds/" + refundId + "/approve?remark=ok", admin, null);
        assertThat(approved.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(approved.path("refundedAt").asText()).isNotBlank();
        assertThat(data("/orders/" + orderId, student).path("status").asText()).isEqualTo("REFUNDED");

        JsonNode afterRefund = data("/entitlements", student).get(0);
        assertThat(afterRefund.path("status").asText()).isEqualTo("REVOKED");
        assertThat(afterRefund.path("totalAmount").asInt())
                .as("已使用部分不回收，只作废剩余额度")
                .isEqualTo(afterRefund.path("usedAmount").asInt());

        long revokeLedgers = countChangeType(data("/entitlements/ledger", student), "REVOKE");
        assertThat(revokeLedgers).isEqualTo(1);

        // 重复审核 → 幂等，不重复回收
        data(HttpMethod.POST, "/admin/refunds/" + refundId + "/approve?remark=ok", admin, null);
        assertThat(countChangeType(data("/entitlements/ledger", student), "REVOKE")).isEqualTo(1);

        // 已作废的权益不能继续使用
        String unavailable = errorCode(HttpMethod.POST,
                "/entitlements/" + accountId + "/consume?amount=1&requestId=refund-consume-2", student, null);
        assertThat(unavailable).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    }

    @Test
    @DisplayName("审核驳回后订单恢复可用，可再次申请退款")
    void rejectShouldKeepOrderUsable() {
        String student = wechatToken("refund-reject-student", "驳回测试考生");
        String admin = adminToken();
        JsonNode product = firstOnSaleProduct(student);
        long orderId = paidOrder(student, product, 1);

        long firstRefundId = data(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "不想要了")).path("id").asLong();
        JsonNode rejected = data(HttpMethod.POST, "/admin/refunds/" + firstRefundId + "/reject?remark=不符合规则",
                admin, null);
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(data("/orders/" + orderId, student).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data("/entitlements", student).get(0).path("status").asText()).isEqualTo("AVAILABLE");

        // 驳回后可重新申请
        JsonNode again = data(HttpMethod.POST, "/orders/" + orderId + "/refunds", student,
                Map.of("reason", "再次申请"));
        assertThat(again.path("status").asText()).isEqualTo("APPLIED");

        // 未支付的订单不可申请退款
        JsonNode unpaid = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", product.path("id").asLong(), "quantity", 1));
        String notPaid = errorCode(HttpMethod.POST, "/orders/" + unpaid.path("id").asLong() + "/refunds",
                student, Map.of("reason", "未支付"));
        assertThat(notPaid).isEqualTo("ORDER_STATUS_INVALID");
    }

    /** 下单 → 支付回调 → 返回已完成的订单 ID */
    private long paidOrder(String student, JsonNode product, int quantity) {
        JsonNode order = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", product.path("id").asLong(), "quantity", quantity));
        long orderId = order.path("id").asLong();
        JsonNode payment = data(HttpMethod.POST, "/orders/" + orderId + "/payments", student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());
        assertThat(data("/orders/" + orderId, student).path("status").asText()).isEqualTo("COMPLETED");
        return orderId;
    }

    private JsonNode firstOnSaleProduct(String token) {
        JsonNode page = data("/products?pageNo=1&pageSize=10", token);
        assertThat(page.path("list").size()).as("应存在上架商品").isGreaterThan(0);
        return page.path("list").get(0);
    }

    private long countChangeType(JsonNode ledgers, String changeType) {
        long count = 0;
        for (JsonNode ledger : ledgers) {
            if (changeType.equals(ledger.path("changeType").asText())) {
                count++;
            }
        }
        return count;
    }
}
