package com.cyx.backend.security;

import com.cyx.backend.config.AuthProperties;
import com.cyx.backend.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final AuthProperties authProperties;

    public JwtService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public String generateToken(UserEntity user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.getTokenTtl());

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public AuthenticatedUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Number userId = claims.get("uid", Number.class);
            String username = claims.getSubject();

            if (userId == null || username == null || username.isBlank()) {
                return null;
            }

            return new AuthenticatedUser(userId.longValue(), username);
        } catch (JwtException | IllegalArgumentException exception) {
            return null;
        }
    }

    private SecretKey signingKey() {
        String secret = authProperties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 密钥不能为空");
        }
        return Keys.hmacShaKeyFor(decodeSecret(secret.trim()));
    }

    private byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
