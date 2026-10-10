package com.minidrive.dto.upload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resumable-upload status read model. {@code uploadedChunks} holds the sorted
 * indexes that have bytes persisted in staging, which lets an interrupted
 * client resume without re-sending them. Staging object keys stay server-side.
 */
public class UploadSessionStatusResponse {

    private final UUID id;
    private final String filename;
    private final String contentType;
    private final long totalSizeBytes;
    private final int chunkSizeBytes;
    private final int totalChunks;
    private final String status;
    private final List<Integer> uploadedChunks;
    private final LocalDateTime expiresAt;

    public UploadSessionStatusResponse(
            UUID id,
            String filename,
            String contentType,
            long totalSizeBytes,
            int chunkSizeBytes,
            int totalChunks,
            String status,
            List<Integer> uploadedChunks,
            LocalDateTime expiresAt) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = totalChunks;
        this.status = status;
        this.uploadedChunks = uploadedChunks;
        this.expiresAt = expiresAt;
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

    public List<Integer> getUploadedChunks() {
        return uploadedChunks;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
