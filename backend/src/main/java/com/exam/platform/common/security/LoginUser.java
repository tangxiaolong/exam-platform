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
    private String roleCode;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(roleCode);
    }

    public boolean isOrgAdmin() {
        return ROLE_ORG_ADMIN.equals(roleCode);
    }
}
