package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色软删 + 微信账号设置初始密码。
 *
 * <p>两件事放一起测，是因为它们共享同一个风险模式：**入口有了，但没有验证过它真的挡住了该挡的**。
 * 角色软删若漏掉「仍有用户持有」这道拦截，表现是一批人突然进不去后台；
 * 设置密码若漏掉「已有密码时拒绝」，表现是任何人能直接覆盖别人的密码。</p>
 */
@DisplayName("角色软删与设置密码")
class RoleDeletionAndPasswordSetupTest extends ApiTestSupport {

    @Test
    @DisplayName("自定义角色可软删，内置角色不允许")
    void customRoleCanBeDeletedButBuiltInCannot() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/roles", admin, Map.of(
                "code", "CUSTOM_DEL", "name", "待删角色", "description", "测试用"));

        data(HttpMethod.DELETE, "/admin/roles/CUSTOM_DEL", admin, null);
        assertThat(roleCodes(admin)).doesNotContain("CUSTOM_DEL");

        assertThat(errorCode(HttpMethod.DELETE, "/admin/roles/ADMIN", admin, null))
                .as("内置角色被删掉，整个后台就进不去了").isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("仍有用户持有该角色时不允许删除")
    void roleWithHoldersCannotBeDeleted() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/roles", admin, Map.of(
                "code", "CUSTOM_HOLD", "name", "被持有角色", "description", "测试用"));

        String student = wechatToken("role-holder", "持角色考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);
        data(HttpMethod.POST, "/admin/users/" + userId + "/roles?roleCode=CUSTOM_HOLD", admin, null);

        assertThat(errorCode(HttpMethod.DELETE, "/admin/roles/CUSTOM_HOLD", admin, null))
                .as("删掉后持有者会失去全部权限").isEqualTo("COMMON_BAD_REQUEST");

        // 解除绑定后即可删除
        data(HttpMethod.DELETE, "/admin/users/" + userId + "/roles/CUSTOM_HOLD", admin, null);
        data(HttpMethod.DELETE, "/admin/roles/CUSTOM_HOLD", admin, null);
        assertThat(roleCodes(admin)).doesNotContain("CUSTOM_HOLD");

        // 学生 token 仍然有效（角色被解除但账号没被动过）
        assertThat(data("/wrong-questions", student).size()).isZero();
    }

    @Test
    @DisplayName("微信账号设置密码必须同时给账号名，否则密码用不上")
    void wechatUserShouldSetPasswordWithUsername() {
        String student = wechatToken("setup-pwd", "设密考生");

        // 只给密码：拒绝。wx_ 开头的账号名考生不可能知道，密码设了也用不上
        assertThat(errorCode(HttpMethod.POST, "/users/me/password/setup", student,
                Map.of("newPassword", "StuPass1234")))
                .as("没有账号名，密码等于用不上").isEqualTo("COMMON_BAD_REQUEST");

        // 弱密码
        assertThat(errorCode(HttpMethod.POST, "/users/me/password/setup",
                wechatToken("setup-weak", "弱密码考生"),
                Map.of("newPassword", "123", "username", "weak_user")))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 非法账号名
        assertThat(errorCode(HttpMethod.POST, "/users/me/password/setup",
                wechatToken("setup-bad", "坏账号名考生"),
                Map.of("newPassword", "StuPass1234", "username", "a b")))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 正常设置
        data(HttpMethod.POST, "/users/me/password/setup", student,
                Map.of("newPassword", "StuPass1234", "username", "stu_setup_pwd"));

        // 这一步才是真正的验收：用考生自己知道的账号名 + 新密码登录成功。
        // 只断言「设置成功」会漏掉「设了个用不上的密码」这种完成度假象。
        JsonNode login = data(HttpMethod.POST, "/auth/login", null,
                Map.of("username", "stu_setup_pwd", "password", "StuPass1234"));
        assertThat(login.path("token").asText()).isNotBlank();

        // 已有密码后不允许再走「设置」，否则等于绕过原密码校验直接覆盖
        assertThat(errorCode(HttpMethod.POST, "/users/me/password/setup", student,
                Map.of("newPassword", "Another1234", "username", "other_name")))
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("角色权限可授予、可撤销，撤销后能重新授予")
    void rolePermissionShouldBeGrantedAndRevoked() {
        String admin = adminToken();
        data(HttpMethod.POST, "/admin/roles", admin, Map.of(
                "code", "CUSTOM_PERM", "name", "权限测试角色", "description", "测试用"));

        data(HttpMethod.POST, "/admin/roles/CUSTOM_PERM/permissions?permission=exam:read", admin, null);
        assertThat(data("/admin/roles/CUSTOM_PERM/permissions", admin).size())
                .as("授予后应有一条权限").isEqualTo(1);

        data(HttpMethod.DELETE, "/admin/roles/CUSTOM_PERM/permissions?permission=exam:read", admin, null);
        assertThat(data("/admin/roles/CUSTOM_PERM/permissions", admin).size())
                .as("撤销后应为空").isZero();

        // 关键：逻辑删除后重新授予。若表上的唯一约束不含 deleted 列，
        // 这里会撞约束而不是恢复权限——那正是「撤掉后授不回来」的隐蔽故障
        data(HttpMethod.POST, "/admin/roles/CUSTOM_PERM/permissions?permission=exam:read", admin, null);
        assertThat(data("/admin/roles/CUSTOM_PERM/permissions", admin).size())
                .as("撤销后必须能重新授予").isEqualTo(1);

        // 撤销一个从未授予的权限：必须报错而不是静默成功。
        // 静默成功会让管理员无法区分「撤掉了」和「本来就没有」，也就不会去查真正的原因
        assertThat(errorCode(HttpMethod.DELETE,
                "/admin/roles/CUSTOM_PERM/permissions?permission=exam:never-granted", admin, null))
                .as("撤销不存在的权限应明确报 404").isEqualTo("COMMON_NOT_FOUND");
    }

    // ---------------------------------------------------------------- helpers

    private List<String> roleCodes(String admin) {
        JsonNode roles = data("/admin/roles", admin);
        List<String> codes = new java.util.ArrayList<>();
        for (JsonNode role : roles) {
            codes.add(role.path("code").asText());
        }
        return codes;
    }
}
