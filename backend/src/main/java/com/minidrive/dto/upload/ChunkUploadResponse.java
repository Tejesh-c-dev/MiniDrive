package com.minidrive.dto.upload;

import java.util.UUID;

/**
 * Result of ingesting a single chunk. The staging object key is deliberately
 * omitted; the client only learns which index landed, at what size, and whether
 * the upload was a fresh write or an idempotent retry of an existing chunk.
 */
public class ChunkUploadResponse {

    private final UUID sessionId;
    private final int chunkIndex;
    private final long sizeBytes;
    private final boolean duplicate;

    public ChunkUploadResponse(
            UUID sessionId,
            int chunkIndex,
            long sizeBytes,
            boolean duplicate) {
        this.sessionId = sessionId;
        this.chunkIndex = chunkIndex;
        this.sizeBytes = sizeBytes;
        this.duplicate = duplicate;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isDuplicate() {
        return duplicate;
    }
}
