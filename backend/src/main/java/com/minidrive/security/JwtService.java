package com.minidrive.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

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

    public String generateToken(String email) {
        return generateToken(email, 0L);
    }

    public String generateToken(String email, long authVersion) {

        Date now = new Date();

        return Jwts.builder()
                .subject(email)
                .id(UUID.randomUUID().toString())
                .claim("auth_version", authVersion)
                .claim("iat_ms", now.getTime())
                .issuedAt(now)
                .expiration(
                        new Date(now.getTime() + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    public String extractUsername(String token) {

        return parseValidatedToken(token).subject();
    }

    /** Parses and verifies a signed, unexpired token with the required identifier. */
    public ValidatedToken parseValidatedToken(String token) {
        Claims claims = extractAllClaims(token);
        String subject = claims.getSubject();
        String tokenId = claims.getId();
        Date expiration = claims.getExpiration();
        if (subject == null || subject.isBlank() || tokenId == null || tokenId.isBlank() || expiration == null) {
            throw new IllegalArgumentException("JWT is missing required claims");
        }
        return new ValidatedToken(subject, tokenId, expiration.toInstant());
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

            parseValidatedToken(token);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    public record ValidatedToken(String subject, String tokenId, Instant expiresAt) {}

    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
