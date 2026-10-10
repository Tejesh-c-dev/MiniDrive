package com.minidrive.dto.file;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response for registering file metadata ahead of the Phase 4 (MinIO)
 * upload flow. The object key is a placeholder reserved for the future
 * object; no binary data is stored yet.
 */
public class FileUploadResponse {

    /**
     * Metadata is registered but the object bytes are not stored yet.
     */
    public static final String UPLOAD_STATUS_PENDING = "PENDING";

    private UUID id;
    private String name;
    private UUID folderId;
    private UUID ownerId;
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    private String checksum;
    private String uploadStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Latest stored content version after this operation, derived from the
     * file's version history. {@code 0} means metadata is registered but no
     * content has been stored yet.
     */
    private int currentVersion;

    public FileUploadResponse(
            UUID id,
            String name,
            UUID folderId,
            UUID ownerId,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String checksum,
            String uploadStatus,
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
        this.uploadStatus = uploadStatus;
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

    public String getUploadStatus() {
        return uploadStatus;
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
