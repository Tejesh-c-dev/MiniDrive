package com.minidrive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(String tokenHash, UUID familyId, UUID userId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getTokenHash() { return tokenHash; }
    public UUID getFamilyId() { return familyId; }
    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void revoke(Instant at, String replacementHash) {
        this.revokedAt = at;
        this.replacedByHash = replacementHash;
    }
}
