package com.minidrive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Stores only the JWT identifier and expiry needed for durable revocation. */
@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {
    @Id
    @Column(name = "jti", length = 36, nullable = false, updatable = false)
    private String tokenId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedToken() {}

    public RevokedToken(String tokenId, Instant expiresAt) {
        this.tokenId = tokenId;
        this.expiresAt = expiresAt;
    }

    public String getTokenId() { return tokenId; }
    public Instant getExpiresAt() { return expiresAt; }
}
