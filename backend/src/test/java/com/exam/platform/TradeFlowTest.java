package com.exam.platform;

import com.exam.platform.modules.trade.TradeService;
import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("交易闭环：下单 → 支付 → 回调验签 → 权益发放")
class TradeFlowTest extends ApiTestSupport {

    @Autowired
    private TradeService tradeService;

    @Test
    @DisplayName("客户端篡改价格不生效，订单金额以服务端商品为准")
    void orderAmountShouldComeFromServer() {
        String student = wechatToken("trade-price-student", "改价测试考生");
        JsonNode product = firstOnSaleProduct(student);

        JsonNode order = data(HttpMethod.POST, "/orders", student, Map.of(
                "productId", product.path("id").asLong(),
                "quantity", 1,
                "payAmountFen", 1,
                "totalAmountFen", 1,
                "discountAmountFen", 999999));

        assertThat(order.path("payAmountFen").asLong())
                .as("服务端必须按商品价格重新计算")
                .isEqualTo(product.path("priceFen").asLong());
        assertThat(order.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.path("expireAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("回调验签失败不得置为已支付；重复回调只发一次权益")
    void paymentNotifyShouldVerifyAndBeIdempotent() {
        String student = wechatToken("trade-pay-student", "支付测试考生");
        JsonNode product = firstOnSaleProduct(student);
        JsonNode order = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", product.path("id").asLong(), "quantity", 1));
        long orderId = order.path("id").asLong();
        long amountFen = order.path("payAmountFen").asLong();

        JsonNode payment = data(HttpMethod.POST, "/orders/" + orderId + "/payments", student, null);
        String payNo = payment.path("payNo").asText();
        assertThat(payNo).isNotBlank();

        // 1. 签名错误 → 拒绝，订单保持待支付
        String badSignCode = errorCode(HttpMethod.POST, "/payments/wechat/notify", null, Map.of(
                "outTradeNo", payNo,
                "transactionId", "TXN-FAKE-" + payNo,
                "amountFen", amountFen,
                "currency", "CNY",
                "tradeState", "SUCCESS",
                "sign", "invalid-sign"));
        assertThat(badSignCode).isEqualTo("PAY_SIGN_INVALID");
        assertThat(data("/orders/" + orderId, student).path("status").asText())
                .isEqualTo("PENDING_PAYMENT");

        // 2. 金额被篡改（签名本身合法）→ 拒绝，订单仍为待支付
        String amountMismatch = errorCode(HttpMethod.POST, "/payments/wechat/notify", null, Map.of(
                "outTradeNo", payNo,
                "transactionId", "TXN-TAMPER-" + payNo,
                "amountFen", amountFen + 1,
                "currency", "CNY",
                "tradeState", "SUCCESS",
                "sign", tradeService.sign(payNo, amountFen + 1)));
        assertThat(amountMismatch).isEqualTo("PAY_AMOUNT_MISMATCH");
        assertThat(data("/orders/" + orderId, student).path("status").asText())
                .isEqualTo("PENDING_PAYMENT");

        // 3. 正常回调（与真实微信支付共用同一条处理链路）→ 订单完成、权益到账
        JsonNode first = dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());
        assertThat(first.path("success").asBoolean()).isTrue();

        JsonNode orderAfter = data("/orders/" + orderId, student);
        assertThat(orderAfter.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(orderAfter.path("paidAt").asText()).isNotBlank();

        // 4. 重复回调 → 不重复发放权益
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());
        JsonNode entitlements = data("/entitlements", student);
        JsonNode ledgers = data("/entitlements/ledger", student);
        assertThat(entitlements.size()).isEqualTo(1);
        assertThat(ledgers.size()).isEqualTo(1);
        assertThat(ledgers.get(0).path("changeAmount").asInt()).isEqualTo(1);
    }

    private JsonNode firstOnSaleProduct(String token) {
        JsonNode page = data("/products?pageNo=1&pageSize=10", token);
        assertThat(page.path("list").size()).as("应存在上架商品").isGreaterThan(0);
        return page.path("list").get(0);
    }
}
