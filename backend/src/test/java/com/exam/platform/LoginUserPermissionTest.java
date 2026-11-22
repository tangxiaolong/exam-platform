package com.exam.platform;

import com.exam.platform.common.security.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限码判定逻辑。
 *
 * <p>这段逻辑在接入 {@code OrgPortalController.grant} 之前，从未被任何业务路径执行过——
 * 它一直是「定义好但没人调用」的状态。纯单元测试先把它钉死，
 * 因为接口级的 403 映射依赖它正确。</p>
 */
@DisplayName("权限码判定")
class LoginUserPermissionTest {

    private LoginUser userWith(Set<String> permissions) {
        LoginUser user = new LoginUser();
        user.setPermissions(permissions);
        return user;
    }

    @Test
    @DisplayName("通配 * 放行任意权限码")
    void wildcardGrantsAll() {
        assertThat(userWith(Set.of("*")).hasPermission("org:grant")).isTrue();
        assertThat(userWith(Set.of("*")).hasPermission("anything:whatever")).isTrue();
    }

    @Test
    @DisplayName("org:* 只放行同域，不越界到别的域")
    void prefixWildcardScopedToDomain() {
        assertThat(userWith(Set.of("org:*")).hasPermission("org:grant")).isTrue();
        assertThat(userWith(Set.of("org:*")).hasPermission("exam:manage"))
                .as("前缀通配如果越域，机构管理员就能改题库了").isFalse();
    }

    @Test
    @DisplayName("精确码只放行自身")
    void exactCodeOnlyGrantsItself() {
        assertThat(userWith(Set.of("org:read")).hasPermission("org:read")).isTrue();
        assertThat(userWith(Set.of("org:read")).hasPermission("org:grant"))
                .as("只读权限不能变成发放权限").isFalse();
    }

    @Test
    @DisplayName("没有权限一律拒绝——拒绝路径的核心")
    void emptyPermissionsRejectEverything() {
        assertThat(userWith(Set.of()).hasPermission("org:grant")).isFalse();
        assertThat(userWith(null).hasPermission("org:grant"))
                .as("permissions 为 null 时不能当成「全通」").isFalse();
    }

    @Test
    @DisplayName("null 权限码不被当成通配")
    void nullPermissionIsRejected() {
        assertThat(userWith(Set.of("*")).hasPermission(null))
                .as("传 null 被当成放行，会变成一道谁都能过的门").isFalse();
    }
}
