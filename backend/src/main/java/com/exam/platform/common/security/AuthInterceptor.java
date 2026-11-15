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
