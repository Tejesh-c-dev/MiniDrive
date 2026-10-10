package com.minidrive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One successfully ingested chunk of an {@link UploadSession}.
 *
 * <p>A row exists only after its bytes have been written to object storage, so
 * the presence of a row is the resume signal. The
 * {@code (session_id, chunk_index)} pair is unique: re-uploading the same index
 * is an idempotent retry rather than a duplicate, while distinct indexes can be
 * uploaded concurrently.</p>
 *
 * <p>{@link #stagingObjectKey} is server-generated and never returned to
 * clients. It is deterministic for a given session and index, so a retry
 * overwrites the same staging object instead of orphaning the previous one.</p>
 */
@Entity
@Table(
        name = "upload_chunks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_upload_chunks_session_index",
                        columnNames = {"session_id", "chunk_index"}),
                @UniqueConstraint(
                        name = "uq_upload_chunks_staging_object_key",
                        columnNames = "staging_object_key")
        })
public class UploadChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UploadSession session;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Server-computed content hash, used to detect a corrupted retry. */
    @Column
    private String checksum;

    @Column(name = "staging_object_key", nullable = false)
    private String stagingObjectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UploadChunk() {
    }

    public UploadChunk(
            UploadSession session,
            int chunkIndex,
            long sizeBytes,
            String checksum,
            String stagingObjectKey) {
        this.session = session;
        this.chunkIndex = chunkIndex;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.stagingObjectKey = stagingObjectKey;
    }

    public UUID getId() {
        return id;
    }

    public UploadSession getSession() {
        return session;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getStagingObjectKey() {
        return stagingObjectKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
