package com.minidrive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A revocable, optionally expiring public share link for a single file.
 *
 * <p>Only the SHA-256 hash of the bearer token is persisted; the raw token is
 * returned to the owner exactly once at creation time and can never be
 * recovered from this row. The {@code token_hash} column is unique so a token
 * can never resolve to more than one link.</p>
 *
 * <p>Revocation physically deletes the row, so a revoked token stops resolving
 * immediately. Expiration is enforced by the server on every public request.</p>
 */
@Entity
@Table(
        name = "share_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_share_links_token_hash",
                columnNames = "token_hash"))
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private File file;

    /** SHA-256 hash (lowercase hex) of the raw bearer token. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Null means the link never expires. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public ShareLink() {
    }

    public ShareLink(File file, String tokenHash, LocalDateTime expiresAt) {
        this.file = file;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
