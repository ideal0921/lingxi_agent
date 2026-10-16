package com.lingxi.common.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import com.lingxi.common.exception.UnauthorizedException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 校验器（M5-5.6）：校验 HS256 签名与时效，提取身份声明。
 *
 * <p>仅接受 HS256（显式限定算法，拒绝算法混淆与 `alg=none`）；任何失败统一抛
 * {@link UnauthorizedException}，对外不区分原因。</p>
 */
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 密钥缺失或长度不足 32 字节（LINGXI_JWT_SECRET）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验并解析令牌。
     *
     * @throws UnauthorizedException 签名不合法、已过期、缺身份声明
     */
    public JwtClaims verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            JwtClaims parsed = new JwtClaims(
                    text(claims.get("tid", String.class)),
                    text(claims.getSubject()),
                    text(claims.get("username", String.class)),
                    text(claims.get("role", String.class)),
                    claims.getExpiration() == null ? 0L : claims.getExpiration().getTime());
            if (!parsed.hasIdentity()) {
                throw new UnauthorizedException("身份凭证缺少必要声明");
            }
            return parsed;
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("身份凭证无效或已过期");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
