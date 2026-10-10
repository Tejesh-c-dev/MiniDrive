package com.minidrive.dto.file;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One entry in a file's version history. This is an explicit read model rather
 * than the {@code FileVersion} entity so the API never leaks internal storage
 * details such as the object key, the MinIO bucket, or credentials.
 */
public class FileVersionResponse {

    private final UUID id;
    private final int versionNumber;
    private final String originalFilename;
    private final String contentType;
    private final long sizeBytes;
    private final UUID createdById;
    private final String createdByName;
    private final String createdByEmail;
    private final LocalDateTime createdAt;

    public FileVersionResponse(
            UUID id,
            int versionNumber,
            String originalFilename,
            String contentType,
            long sizeBytes,
            UUID createdById,
            String createdByName,
            String createdByEmail,
            LocalDateTime createdAt) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.createdByEmail = createdByEmail;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public UUID getCreatedById() {
        return createdById;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public String getCreatedByEmail() {
        return createdByEmail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
