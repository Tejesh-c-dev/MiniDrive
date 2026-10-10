package com.minidrive.dto.upload;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Acknowledges a newly initialized chunked upload. It intentionally exposes
 * only what the client needs to drive the upload: the session id, the
 * server-selected chunk size, the expected chunk count and the current status.
 * Staging object keys are never included.
 */
public class UploadSessionResponse {

    private final UUID id;
    private final String filename;
    private final String contentType;
    private final UUID folderId;
    private final UUID targetFileId;
    private final long totalSizeBytes;
    private final int chunkSizeBytes;
    private final int totalChunks;
    private final String status;
    private final LocalDateTime expiresAt;
    private final LocalDateTime createdAt;

    public UploadSessionResponse(
            UUID id,
            String filename,
            String contentType,
            UUID folderId,
            UUID targetFileId,
            long totalSizeBytes,
            int chunkSizeBytes,
            int totalChunks,
            String status,
            LocalDateTime expiresAt,
            LocalDateTime createdAt) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.folderId = folderId;
        this.targetFileId = targetFileId;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = totalChunks;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public UUID getTargetFileId() {
        return targetFileId;
    }

    public long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
