package com.exam.platform.common.security;

import com.exam.platform.common.exception.BizException;

/**
 * 请求级登录上下文。
 *
 * <p>多租户隔离的数据来源：tenant_id 只能来自这里（即校验过的 token），
 * 绝不接受客户端显式传入的 tenant_id。</p>
 */
public final class UserContext {

    /** 平台默认租户，用于未登录请求（登录接口、公开接口）与平台级初始化数据。 */
    public static final long PLATFORM_TENANT_ID = 1L;

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static long tenantId() {
        LoginUser user = HOLDER.get();
        return user == null || user.getTenantId() == null ? PLATFORM_TENANT_ID : user.getTenantId();
    }

    public static long userId() {
        LoginUser user = requireLogin();
        return user.getUserId();
    }

    public static LoginUser requireLogin() {
        LoginUser user = HOLDER.get();
        if (user == null || user.getUserId() == null) {
            throw new BizException("AUTH_UNAUTHORIZED", "请先登录");
        }
        return user;
    }

    public static void requireAdmin() {
        if (!requireLogin().isAdmin()) {
            throw BizException.forbidden("需要平台管理员权限");
        }
    }

    /**
     * 权限码校验：{@code *} 全通配、{@code org:*} 前缀通配、其余精确匹配。
     *
     * <p>新增接口时用它替代「只判断角色」——角色是粗粒度门票，
     * 权限码才能表达「同一个机构管理员可以发权益但不能改机构配置」。</p>
     */
    public static void requirePermission(String permission) {
        if (!requireLogin().hasPermission(permission)) {
            throw BizException.forbidden("缺少权限：" + permission);
        }
    }
}
