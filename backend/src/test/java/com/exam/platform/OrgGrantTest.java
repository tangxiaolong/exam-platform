package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("机构/企业批量授权：组织成员维度发放权益")
class OrgGrantTest extends ApiTestSupport {

    @Test
    @DisplayName("批量授权给全部有效成员，重复提交不重复发放")
    void grantToAllActiveMembers() {
        String admin = adminToken();

        // 两个考生加入同一企业组织
        String studentA = wechatToken("org-grant-a", "机构考生A");
        String studentB = wechatToken("org-grant-b", "机构考生B");
        long userA = data("/users/me", studentA).path("id").asLong();
        long userB = data("/users/me", studentB).path("id").asLong();

        long orgId = createOrg(admin, "ORG-TEST-1", "测试企业");
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, Map.of("userId", userA));
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, Map.of("userId", userB));

        JsonNode result = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/grants", admin, Map.of(
                "entitlementType", "QUESTION_BANK",
                "amount", 2));
        assertThat(result.path("targetCount").asInt()).isEqualTo(2);
        assertThat(result.path("grantedCount").asInt()).isEqualTo(2);
        assertThat(result.path("skippedCount").asInt()).isZero();
        assertThat(result.path("batchNo").asText()).isNotBlank();

        // 两名成员都拿到权益
        assertThat(data("/entitlements", studentA).get(0).path("totalAmount").asInt()).isEqualTo(2);
        assertThat(data("/entitlements", studentB).get(0).path("totalAmount").asInt()).isEqualTo(2);

        // 再授权一批（新批次号）→ 额度叠加，而不是被去重吞掉
        JsonNode second = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/grants", admin, Map.of(
                "entitlementType", "QUESTION_BANK", "amount", 1));
        assertThat(second.path("batchNo").asText()).isNotEqualTo(result.path("batchNo").asText());
        assertThat(data("/entitlements", studentA).get(0).path("totalAmount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("指定成员授权时忽略非本组织成员")
    void grantToSpecificMembersShouldIgnoreOutsiders() {
        String admin = adminToken();
        String insider = wechatToken("org-insider", "内部成员");
        String outsider = wechatToken("org-outsider", "外部用户");
        long insiderId = data("/users/me", insider).path("id").asLong();
        long outsiderId = data("/users/me", outsider).path("id").asLong();

        long orgId = createOrg(admin, "ORG-TEST-2", "测试机构");
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, Map.of("userId", insiderId));

        JsonNode result = data(HttpMethod.POST, "/admin/orgs/" + orgId + "/grants", admin, Map.of(
                "entitlementType", "MEMBER",
                "amount", 1,
                "userIds", java.util.List.of(insiderId, outsiderId)));
        assertThat(result.path("grantedCount").asInt()).isEqualTo(1);
        assertThat(result.path("targetCount").asInt()).isEqualTo(1);

        assertThat(data("/entitlements", insider).size()).isPositive();
        assertThat(data("/entitlements", outsider).size()).isZero();
    }

    @Test
    @DisplayName("停用组织不能授权；无成员组织授权报错")
    void guardRules() {
        String admin = adminToken();

        long emptyOrg = createOrg(admin, "ORG-TEST-EMPTY", "空组织");
        assertThat(errorCode(HttpMethod.POST, "/admin/orgs/" + emptyOrg + "/grants", admin,
                Map.of("entitlementType", "MEMBER", "amount", 1))).isEqualTo("ORG_NO_MEMBER");

        long disabledOrg = createOrg(admin, "ORG-TEST-OFF", "停用组织", "DISABLED");
        String student = wechatToken("org-disabled-student", "停用组织考生");
        long studentId = data("/users/me", student).path("id").asLong();
        data(HttpMethod.POST, "/admin/orgs/" + disabledOrg + "/members", admin, Map.of("userId", studentId));
        assertThat(errorCode(HttpMethod.POST, "/admin/orgs/" + disabledOrg + "/grants", admin,
                Map.of("entitlementType", "MEMBER", "amount", 1))).isEqualTo("ORG_DISABLED");

        // 非法权益类型
        assertThat(errorCode(HttpMethod.POST, "/admin/orgs/" + emptyOrg + "/grants", admin,
                Map.of("entitlementType", "UNKNOWN", "amount", 1))).isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("成员移出后不再被批量授权；重复加入同一组织幂等")
    void removedMemberShouldNotBeGranted() {
        String admin = adminToken();
        String student = wechatToken("org-remove-student", "移出成员");
        long userId = data("/users/me", student).path("id").asLong();

        long orgId = createOrg(admin, "ORG-TEST-REMOVE", "成员移出组织");
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, Map.of("userId", userId));
        // 重复加入幂等
        data(HttpMethod.POST, "/admin/orgs/" + orgId + "/members", admin, Map.of("userId", userId));
        assertThat(data("/admin/orgs/" + orgId + "/members", admin).size()).isEqualTo(1);

        data(HttpMethod.DELETE, "/admin/orgs/" + orgId + "/members/" + userId, admin, null);

        String code = errorCode(HttpMethod.POST, "/admin/orgs/" + orgId + "/grants", admin,
                Map.of("entitlementType", "COURSE", "amount", 1));
        assertThat(code).isEqualTo("ORG_NO_MEMBER");
        assertThat(data("/entitlements", student).size()).isZero();
    }

    private long createOrg(String admin, String code, String name) {
        return createOrg(admin, code, name, "ENABLED");
    }

    private long createOrg(String admin, String code, String name, String status) {
        return data(HttpMethod.POST, "/admin/orgs", admin, Map.of(
                "code", code,
                "name", name,
                "type", "ENTERPRISE",
                "status", status)).path("id").asLong();
    }
}
