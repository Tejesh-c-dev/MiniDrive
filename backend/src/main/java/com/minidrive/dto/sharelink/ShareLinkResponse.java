package com.minidrive.dto.sharelink;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata for an existing share link. This deliberately never contains the
 * raw bearer token or its stored hash: the raw token is returned only once at
 * creation time and cannot be recovered afterwards.
 */
public class ShareLinkResponse {

    private final UUID id;
    private final UUID fileId;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;
    private final boolean expired;

    public ShareLinkResponse(
            UUID id,
            UUID fileId,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            boolean expired) {
        this.id = id;
        this.fileId = fileId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.expired = expired;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFileId() {
        return fileId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return expired;
    }
}
