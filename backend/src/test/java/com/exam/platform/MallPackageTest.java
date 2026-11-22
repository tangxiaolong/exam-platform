package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD 3.1 商城「套餐」第一步：PACKAGE 商品可创建、商城端可见、价格=套餐自身定价。
 * 组合明细表（mall_package_item）已随 V22 建好，逐件履约属第二步。
 */
@DisplayName("商城套餐（PACKAGE 商品）")
class MallPackageTest extends ApiTestSupport {

    @Test
    @DisplayName("套餐商品在商城可见且价格不变")
    void packageProductVisible() {
        String admin = adminToken();
        String student = wechatToken("package-student", "套餐考生");

        var created = data(HttpMethod.POST, "/admin/products", admin, Map.of(
                "code", "PKG-VIP-CET4",
                "name", "四级备考套餐",
                "type", "PACKAGE",
                "description", "会员 + 题库包组合",
                "priceFen", 12900,
                "validityDays", 365,
                "status", "ON_SALE"
        ));
        long id = created.isObject() ? created.path("id").asLong() : created.asLong();

        var products = data("/products?pageNo=1&pageSize=20", student);
        long foundPrice = -1;
        for (var row : products.path("list")) {
            if (row.path("id").asLong() == id) {
                assertThat(row.path("type").asText()).isEqualTo("PACKAGE");
                foundPrice = row.path("priceFen").asLong();
            }
        }
        assertThat(foundPrice).as("套餐在商城可见且价格为套餐自身定价").isEqualTo(12900);
    }
}
