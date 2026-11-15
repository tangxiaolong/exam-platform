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
            return user;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException("AUTH_INVALID_TOKEN", "登录状态已失效，请重新登录");
        }
    }

    public long expireSeconds() {
        return expireMinutes * 60;
    }
}
