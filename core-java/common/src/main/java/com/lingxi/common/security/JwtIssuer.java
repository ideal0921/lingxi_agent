package com.lingxi.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发器（M5-5.6）：HS256 签名，密钥来自环境变量（与 Go 网关共享同一密钥，故网关可验签）。
 *
 * <p>安全约束：密钥长度必须 ≥ 32 字节（HS256 最低强度要求），否则签发即拒绝——
 * 宁可启动失败，也不产生弱签名令牌。</p>
 */
public class JwtIssuer {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long tokenTtlSeconds;

    public JwtIssuer(String secret, long tokenTtlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 密钥缺失或长度不足 " + MIN_SECRET_BYTES + " 字节（LINGXI_JWT_SECRET）");
        }
        if (tokenTtlSeconds <= 0) {
            throw new IllegalStateException("令牌有效期必须为正数（LINGXI_AUTH_TOKEN_TTL_SECONDS）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /** 令牌有效期（秒），用于响应体告知前端。 */
    public long tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    /** 签发令牌：claims 与网关约定一致（tid/sub/username/role/iat/exp）。 */
    public String issue(String tenantId, String userId, String username, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(tokenTtlSeconds);
        return Jwts.builder()
                .subject(userId)
                .claim("tid", tenantId)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
