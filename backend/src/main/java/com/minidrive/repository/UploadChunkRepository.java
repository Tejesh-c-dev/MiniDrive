package com.minidrive.repository;

import com.minidrive.entity.UploadChunk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadChunkRepository extends JpaRepository<UploadChunk, UUID> {

    /**
     * Looks up one chunk within a session. Because {@code (session_id,
     * chunk_index)} is unique this returns at most one row, which is what makes
     * a same-index retry idempotent.
     */
    Optional<UploadChunk> findBySessionIdAndChunkIndex(UUID sessionId, int chunkIndex);

    /** All persisted chunks for a session, ordered for a deterministic resume map. */
    List<UploadChunk> findBySessionIdOrderByChunkIndexAsc(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}
