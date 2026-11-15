package com.exam.platform.modules.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.TokenService;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.auth.dto.LoginRequest;
import com.exam.platform.modules.auth.dto.LoginResponse;
import com.exam.platform.modules.auth.dto.WechatLoginRequest;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.entity.SysUserIdentity;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import com.exam.platform.modules.system.mapper.SysUserIdentityMapper;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.system.support.UserViews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 登录服务。
 *
 * <p>微信登录：真实环境应调用 {@code jscode2session} 换取 openid；本地联调时
 * {@code app.wechat.mock=true} 会由 code 派生稳定 openid，接口契约与真实环境一致。</p>
 */
@Slf4j
@Service
public class AuthService {

    private static final String PROVIDER_WECHAT = "WECHAT";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String DEFAULT_TENANT_CODE = "PLATFORM";

    private final SysUserMapper userMapper;
    private final SysUserIdentityMapper identityMapper;
    private final SysTenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final boolean wechatMock;

    public AuthService(SysUserMapper userMapper,
                       SysUserIdentityMapper identityMapper,
                       SysTenantMapper tenantMapper,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       @Value("${app.wechat.mock:true}") boolean wechatMock) {
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.tenantMapper = tenantMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.wechatMock = wechatMock;
    }

    @Transactional
    public LoginResponse loginByPassword(LoginRequest request) {
        applyTenant(request.tenantCode());
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.username()));
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException("AUTH_BAD_CREDENTIALS", "账号或密码错误");
        }
        assertEnabled(user);
        return issueAndTouch(user);
    }

    /**
     * 登录前确定租户上下文：账号名在租户内唯一，因此必须先定位租户再查用户，
     * 否则会命中上一个请求残留的默认租户。
     */
    private void applyTenant(String tenantCode) {
        String code = (tenantCode == null || tenantCode.isBlank()) ? DEFAULT_TENANT_CODE : tenantCode.trim();
        SysTenant tenant = tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getCode, code));
        if (tenant == null) {
            throw new BizException("TENANT_NOT_FOUND", "租户不存在：" + code);
        }
        if (!STATUS_ENABLED.equals(tenant.getStatus())) {
            throw new BizException("TENANT_DISABLED", "租户已停用");
        }
        LoginUser context = new LoginUser();
        context.setTenantId(tenant.getId());
        UserContext.set(context);
    }

    @Transactional
    public LoginResponse loginByWechat(WechatLoginRequest request) {
        String openId = resolveOpenId(request.code());
        SysUserIdentity identity = identityMapper.selectOne(Wrappers.<SysUserIdentity>lambdaQuery()
                .eq(SysUserIdentity::getProvider, PROVIDER_WECHAT)
                .eq(SysUserIdentity::getOpenId, openId));

        SysUser user;
        if (identity == null) {
            user = createWechatUser(openId, request);
            identity = new SysUserIdentity();
            identity.setUserId(user.getId());
            identity.setProvider(PROVIDER_WECHAT);
            identity.setOpenId(openId);
            identityMapper.insert(identity);
        } else {
            user = userMapper.selectById(identity.getUserId());
            if (user == null) {
                throw new BizException("AUTH_USER_MISSING", "账号已被禁用，请联系管理员");
            }
            // 昵称头像以小程序最新授权为准
            if (request.nickname() != null && !request.nickname().isBlank()) {
                user.setNickname(request.nickname());
            }
            if (request.avatar() != null && !request.avatar().isBlank()) {
                user.setAvatar(request.avatar());
            }
        }
        assertEnabled(user);
        return issueAndTouch(user);
    }

    private SysUser createWechatUser(String openId, WechatLoginRequest request) {
        SysUser user = new SysUser();
        user.setTenantId(UserContext.tenantId());
        user.setUsername("wx_" + openId.substring(Math.max(0, openId.length() - 12)));
        user.setNickname(request.nickname() == null || request.nickname().isBlank()
                ? "微信用户" + openId.substring(Math.max(0, openId.length() - 4)) : request.nickname());
        user.setAvatar(request.avatar());
        user.setRoleCode(LoginUser.ROLE_STUDENT);
        user.setStatus(STATUS_ENABLED);
        userMapper.insert(user);
        return user;
    }

    private LoginResponse issueAndTouch(SysUser user) {
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getNickname(),
                user.getTenantId(), user.getRoleCode());
        String token = tokenService.issue(loginUser);
        return new LoginResponse(token, "Bearer", tokenService.expireSeconds(), UserViews.of(user));
    }

    private void assertEnabled(SysUser user) {
        if (!STATUS_ENABLED.equals(user.getStatus())) {
            throw new BizException("AUTH_USER_DISABLED", "账号已被禁用");
        }
    }

    private String resolveOpenId(String code) {
        if (!wechatMock) {
            throw new BizException("AUTH_WECHAT_NOT_CONFIGURED",
                    "未配置微信 appid/secret，请设置 app.wechat.mock=false 并接入 jscode2session");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return "mock-openid-" + HexFormat.of().formatHex(hash).substring(0, 20);
        } catch (Exception e) {
            throw new IllegalStateException("openid 派生失败", e);
        }
    }
}
