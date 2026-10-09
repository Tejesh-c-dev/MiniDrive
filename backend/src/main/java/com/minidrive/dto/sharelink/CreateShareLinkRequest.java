package com.minidrive.dto.sharelink;

import java.time.LocalDateTime;

/**
 * Request to create a public share link for a file. The body is optional; an
 * absent or null {@code expiresAt} creates a link that never expires. A past
 * timestamp is rejected by the service.
 */
public class CreateShareLinkRequest {

    /** Optional expiration instant; null means the link never expires. */
    private LocalDateTime expiresAt;

    public CreateShareLinkRequest() {
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
