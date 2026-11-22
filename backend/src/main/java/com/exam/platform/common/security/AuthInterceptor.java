package com.exam.platform.common.security;

import com.exam.platform.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器：
 * <ul>
 *   <li>解析 {@code Authorization: Bearer <token>} 并写入 {@link UserContext}；</li>
 *   <li>{@code /v1/admin/**} 路径强制要求平台管理员角色；</li>
 *   <li>{@code /v1/org/**} 路径强制要求机构管理员角色。</li>
 * </ul>
 * 白名单路径在 {@code WebMvcConfig} 中排除。
 *
 * <p>两个前缀的角色是互斥的：机构管理员进不了平台后台，平台管理员也不走机构侧接口。
 * 角色只是「门票」，机构侧真正的边界是「当前用户是不是该机构的有效成员」，
 * 那道校验在 {@code OrgPortalService} 里做——只靠角色，一个 ORG_ADMIN
 * 就能改别的机构的数据。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PATH_PREFIX = "/v1/admin/";
    private static final String ORG_PORTAL_PATH_PREFIX = "/v1/org/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final com.exam.platform.modules.system.RoleService roleService;

    /** 账号状态必须每个请求现查：token 里只带身份，不带账号是否被停用。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BizException("AUTH_UNAUTHORIZED", "请先登录");
        }
        LoginUser user = tokenService.parse(header.substring(BEARER_PREFIX.length()).trim());
        UserContext.set(user);

        // 账号被停用或删除后，已签发的 token 必须立刻失效。
        // token 里只带身份，状态必须现查——否则「封禁一个账号」要等它的 token 过期才生效，
        // 而停用本来就是用来应急止血的（离职、盗号、刷单），等几小时等于没做。
        // 一次查询同时取「账号状态」和「token 是否已作废」：
        // 两者都只依赖 sys_user 同一行，拆开就是每个请求多一次 DB 往返
        java.util.Map<String, Object> account = jdbcTemplate.query(
                "SELECT status, token_invalid_before FROM sys_user "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                rs -> {
                    java.util.Map<String, Object> row = new java.util.HashMap<>();
                    if (rs.next()) {
                        row.put("status", rs.getString("status"));
                        row.put("invalidBefore", rs.getTimestamp("token_invalid_before"));
                    }
                    return row;
                },
                UserContext.tenantId(), user.getUserId());
        if (account.isEmpty()) {
            throw new BizException("AUTH_UNAUTHORIZED", "账号不存在或已删除，请重新登录");
        }
        if (!"ENABLED".equals(account.get("status"))) {
            throw new BizException("AUTH_UNAUTHORIZED", "账号已停用，请重新登录");
        }
        java.sql.Timestamp invalidBefore = (java.sql.Timestamp) account.get("invalidBefore");
        // 用毫秒级 iatMs 比较：秒精度下「旧 token 失效」与「新 token 不被误杀」无法同时成立，
        // 只能二选一，而两种结果用户都能感知（改密码没生效 / 改完登录不上）
        if (invalidBefore != null && user.getTokenIssuedAt() != null
                && user.getTokenIssuedAt() <= invalidBefore.getTime()) {
            throw new BizException("AUTH_UNAUTHORIZED", "密码已变更，请重新登录");
        }

        // 角色与权限按请求现查，而不是写进 token：撤权必须立即生效，
        // 否则一个被停用的管理员还能用旧 token 操作到过期为止
        user.setRoles(roleService.rolesOf(user.getUserId()));
        user.setPermissions(roleService.permissionsOf(user.getRoles()));

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        if (path.startsWith(ADMIN_PATH_PREFIX) && !user.isAdmin()) {
            throw BizException.forbidden("需要平台管理员权限");
        }
        if (path.startsWith(ORG_PORTAL_PATH_PREFIX) && !user.isOrgAdmin()) {
            throw BizException.forbidden("需要机构管理员权限");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
