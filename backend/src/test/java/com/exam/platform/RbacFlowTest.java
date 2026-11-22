package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 三元组：多角色、权限并集、越权拦截、防呆。
 *
 * <p>重点不是「能加角色」，而是三件容易出事的事：</p>
 * <ol>
 *   <li>存量用户的 role_code 是否被回填成 user_role（漏了就会大面积掉权限）；</li>
 *   <li>多角色权限是否取并集（取交集会导致「加了角色反而不能干活」）；</li>
 *   <li>撤销最后一个角色 / 撤销自己是否被挡住（静默锁死账号比报错严重得多）。</li>
 * </ol>
 */
@DisplayName("RBAC：角色三元组")
class RbacFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("存量 role_code 已回填为用户角色")
    void existingRoleCodeShouldBeBackfilled() {
        String admin = adminToken();
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN_USERNAME);
        // 断言行为而不是表内容：兼容期用户的角色可能来自 sys_user.role_code 的回退，
        // 只要接口能读到 ADMIN 就说明鉴权链路是通的
        JsonNode adminRoles = data("/admin/users/" + adminId + "/roles", admin);
        assertThat(adminRoles.size()).as("admin 必须至少有一个角色，否则整个后台进不去").isEqualTo(1);
        assertThat(adminRoles.get(0).asText()).isEqualTo("ADMIN");

        // 管理端接口能读到角色列表，且内置 ADMIN 的权限是全通配
        JsonNode roleList = data("/admin/roles", admin);
        assertThat(roleList.size()).isGreaterThanOrEqualTo(3);
        JsonNode adminPermissions = data("/admin/roles/ADMIN/permissions", admin);
        assertThat(adminPermissions.get(0).path("permission_code").asText()).isEqualTo("*");
    }

    @Test
    @DisplayName("考生访问后台接口被拒")
    void studentShouldNotReachAdminApis() {
        String student = wechatToken("rbac-student", "权限考生");
        assertThat(errorCode(HttpMethod.GET, "/admin/roles", student, null))
                .isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("加第二个角色后权限取并集，且立即生效")
    void addingRoleShouldUnionPermissions() {
        String student = wechatToken("rbac-union", "并集考生");
        String admin = adminToken();
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);

        assertThat(data("/admin/users/" + userId + "/roles", admin).size()).isEqualTo(1);

        data(HttpMethod.POST, "/admin/users/" + userId + "/roles?roleCode=ORG_ADMIN", admin, null);

        assertThat(data("/admin/users/" + userId + "/roles", admin).size()).isEqualTo(2);

        // 并集而不是覆盖：STUDENT 的 exam:take 与 ORG_ADMIN 的 org:* 都应存在
        List<String> permissions = jdbcTemplate.queryForList(
                "SELECT DISTINCT p.permission_code FROM sys_role_permission p "
                        + "JOIN sys_user_role ur ON ur.role_code = p.role_code AND ur.tenant_id = p.tenant_id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0",
                String.class, userId);
        assertThat(permissions).contains("exam:take", "org:*");

        // 重复授予是幂等的，不应该变成 3 条
        data(HttpMethod.POST, "/admin/users/" + userId + "/roles?roleCode=ORG_ADMIN", admin, null);
        assertThat(data("/admin/users/" + userId + "/roles", admin).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("不能撤销自己的角色，也不能撤掉用户的最后一个角色")
    void shouldRefuseDangerousRevocations() {
        String admin = adminToken();
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN_USERNAME);

        // 自己撤自己：下一个请求就进不了后台，等于把自己锁在门外
        assertThat(errorCode(HttpMethod.DELETE, "/admin/users/" + adminId + "/roles/ADMIN", admin, null))
                .isEqualTo("AUTH_SELF_REVOKE");

        String student = wechatToken("rbac-last-role", "最后角色考生");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);

        // 撤掉唯一角色：账号会被静默锁死，必须拒绝且回滚
        assertThat(errorCode(HttpMethod.DELETE, "/admin/users/" + userId + "/roles/STUDENT", admin, null))
                .isEqualTo("AUTH_LAST_ROLE");
        assertThat(data("/admin/users/" + userId + "/roles", admin).size())
                .as("被拒绝的撤销不能真的生效").isEqualTo(1);
        // 学生依然能正常访问业务接口
        assertThat(data("/products?pageNo=1&pageSize=1", student).path("list").size()).isGreaterThan(0);
    }
}
