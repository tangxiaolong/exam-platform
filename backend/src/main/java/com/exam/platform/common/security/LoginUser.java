package com.exam.platform.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前登录主体（由 JWT 解析得到，不从请求参数中读取）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_STUDENT = "STUDENT";
    /**
     * 机构管理员：只能通过 {@code /v1/org/**} 操作自己所属的机构。
     *
     * <p>刻意与平台 {@link #ROLE_ADMIN} 分开：机构管理员不应该能进平台后台，
     * 平台管理员也不需要冒充机构身份操作业务；两个入口、两套角色，边界才清楚。</p>
     */
    public static final String ROLE_ORG_ADMIN = "ORG_ADMIN";

    private Long userId;
    private String username;
    private String nickname;
    private Long tenantId;
    /** 主角色：保留是为了兼容既有代码与旧 token，鉴权以 {@link #roles} 为准。 */
    private String roleCode;
    /** 用户的全部角色（多角色，鉴权入口按三元组加载）。 */
    private java.util.Set<String> roles;
    /** 角色权限并集：支持 * 与 org:* 通配。 */
    private java.util.Set<String> permissions;
    /**
     * token 签发时间（毫秒）。用于与 {@code sys_user.token_invalid_before} 比较：
     * 签发早于该时间的 token 一律作废。
     *
     * <p>用 iat 而不是 exp 判断，语义才是「改密码之前签发的全部失效」；
     * 用 exp 只能知道「还能活多久」，无法区分新旧。</p>
     */
    private Long tokenIssuedAt;

    public LoginUser(Long userId, String username, String nickname, Long tenantId, String roleCode) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.tenantId = tenantId;
        this.roleCode = roleCode;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(roleCode) || (roles != null && roles.contains(ROLE_ADMIN));
    }

    public boolean isOrgAdmin() {
        return ROLE_ORG_ADMIN.equals(roleCode) || (roles != null && roles.contains(ROLE_ORG_ADMIN));
    }

    public boolean hasRole(String code) {
        return code != null && ((roles != null && roles.contains(code)) || code.equals(roleCode));
    }

    /**
     * 权限判定。
     *
     * <p>三种写法：{@code *} 全通配、{@code org:*} 前缀通配、精确码。
     * 前缀通配是必要的：管理员每加一个机构接口就补一行权限，配置一定会漏。</p>
     */
    public boolean hasPermission(String permission) {
        if (permission == null || permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains("*") || permissions.contains(permission)) {
            return true;
        }
        int idx = permission.indexOf(':');
        return idx > 0 && permissions.contains(permission.substring(0, idx) + ":*");
    }
}
