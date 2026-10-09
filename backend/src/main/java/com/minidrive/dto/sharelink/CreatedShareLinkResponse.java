package com.minidrive.dto.sharelink;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response returned exactly once when a share link is created. It is the only
 * place the raw {@code token} is ever exposed; the server stores only its
 * SHA-256 hash and cannot reproduce the token later.
 */
public class CreatedShareLinkResponse {

    private final UUID id;
    private final UUID fileId;
    private final String token;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;

    public CreatedShareLinkResponse(
            UUID id,
            UUID fileId,
            String token,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {
        this.id = id;
        this.fileId = fileId;
        this.token = token;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getToken() {
        return token;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
