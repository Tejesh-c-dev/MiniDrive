package com.minidrive.dto.file;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Full file metadata as returned by single-resource reads and updates.
 */
public class FileMetadataResponse {

    private UUID id;
    private String name;
    private UUID folderId;
    private UUID ownerId;
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    private String checksum;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Latest stored content version, derived from the file's version history.
     * {@code 0} means the file has no stored content yet (metadata-only).
     */
    private int currentVersion;

    public FileMetadataResponse(
            UUID id,
            String name,
            UUID folderId,
            UUID ownerId,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String checksum,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int currentVersion) {
        this.id = id;
        this.name = name;
        this.folderId = folderId;
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.createdAt = createdAt;
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

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
