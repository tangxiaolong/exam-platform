package com.exam.platform.common.security;

import com.exam.platform.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/** JWT 签发与校验（HS256）。 */
@Component
public class TokenService {

    private final SecretKey key;
    private final long expireMinutes;

    public TokenService(@Value("${app.jwt.secret}") String secret,
                        @Value("${app.jwt.expire-minutes:720}") long expireMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMinutes = expireMinutes;
    }

    public String issue(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("username", user.getUsername())
                .claim("nickname", user.getNickname())
                .claim("tenantId", user.getTenantId())
                .claim("role", user.getRoleCode())
                .issuedAt(Date.from(now))
                // 标准 iat 只有秒精度，无法区分「同一秒内改密码前后签发的两个 token」。
                // 没有毫秒就只能在「旧 token 不失效」和「新 token 被误杀」之间二选一，
                // 两种都是用户能感知的事故。
                .claim("iatMs", now.toEpochMilli())
                .expiration(Date.from(now.plus(expireMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            LoginUser user = new LoginUser();
            user.setUserId(Long.valueOf(claims.getSubject()));
            user.setUsername(claims.get("username", String.class));
            user.setNickname(claims.get("nickname", String.class));
            Number tenantId = claims.get("tenantId", Number.class);
            user.setTenantId(tenantId == null ? UserContext.PLATFORM_TENANT_ID : tenantId.longValue());
            user.setRoleCode(claims.get("role", String.class));
            // iat 在 issue() 里已写入；留空时保持 null，由拦截器按「无法判定」处理，
            // 不要在这里塞当前时间——那会让所有老 token 看起来都是刚签发的
            // 优先用毫秒级 iatMs；老 token 没有该 claim，回退到秒级 iat（精度差但不会误杀）
            Long issuedAtMs = claims.get("iatMs", Number.class) == null
                    ? null : claims.get("iatMs", Number.class).longValue();
            if (issuedAtMs == null && claims.getIssuedAt() != null) {
                issuedAtMs = claims.getIssuedAt().getTime();
            }
            user.setTokenIssuedAt(issuedAtMs);
            return user;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException("AUTH_INVALID_TOKEN", "登录状态已失效，请重新登录");
        }
    }

    public long expireSeconds() {
        return expireMinutes * 60;
    }
}
