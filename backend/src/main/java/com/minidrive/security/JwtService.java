package com.minidrive.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));

        this.expirationMs = expirationMs;
    }

    public String generateToken(String email, long authVersion) {

        Date now = new Date();

        return Jwts.builder()
                .subject(email)
                .claim("auth_version", authVersion)
                .claim("iat_ms", now.getTime())
                .issuedAt(now)
                .expiration(
                        new Date(now.getTime() + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    public String extractUsername(String token) {

        return extractAllClaims(token).getSubject();
    }

    public Date extractIssuedAt(String token) {
        Claims claims = extractAllClaims(token);
        Number issuedAtMillis = claims.get("iat_ms", Number.class);
        return issuedAtMillis == null ? claims.getIssuedAt() : new Date(issuedAtMillis.longValue());
    }

    public Long extractAuthVersion(String token) {
        Claims claims = extractAllClaims(token);
        Number authVersion = claims.get("auth_version", Number.class);
        return authVersion == null ? null : authVersion.longValue();
    }

    public boolean isTokenValid(String token) {

        try {

            Claims claims = extractAllClaims(token);

            return claims.getSubject() != null
                    && claims.getExpiration().after(new Date());

        } catch (Exception e) {
            return false;
        }
    }

    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
