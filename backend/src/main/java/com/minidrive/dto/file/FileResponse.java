package com.minidrive.dto.file;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Compact file summary used by list endpoints.
 */
public class FileResponse {

    private UUID id;
    private String name;
    private UUID folderId;
    private String contentType;
    private Long sizeBytes;
    private LocalDateTime updatedAt;

    /**
     * Latest stored content version, derived from the file's version history.
     * {@code 0} means the file has no stored content yet (metadata-only).
     */
    private int currentVersion;

    public FileResponse(
            UUID id,
            String name,
            UUID folderId,
            String contentType,
            Long sizeBytes,
            LocalDateTime updatedAt,
            int currentVersion) {
        this.id = id;
        this.name = name;
        this.folderId = folderId;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.updatedAt = updatedAt;
        this.currentVersion = currentVersion;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
