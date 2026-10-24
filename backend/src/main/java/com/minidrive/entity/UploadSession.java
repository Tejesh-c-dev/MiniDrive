package com.minidrive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One in-progress chunked upload, owned by a single user.
 *
 * <p>The row is created before any bytes arrive and records the client's
 * declared metadata together with the server-chosen {@link #chunkSizeBytes}
 * and the derived {@link #totalChunks}. That lets every chunk request be
 * validated (index bounds and exact per-chunk size) without trusting anything
 * the client sends at chunk time, and lets a client resume an interrupted
 * upload by asking which chunks already landed.</p>
 *
 * <p>{@link #targetFile} is set only when the session is meant to replace an
 * existing file's content; {@link #folder} only when the session will create a
 * new file in a folder. Neither is consumed here -- assembly and File/FileVersion
 * persistence belong to Phase 8.4.</p>
 */
@Entity
@Table(name = "upload_sessions", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uq_upload_sessions_owner_idempotency",
        columnNames = {"owner_id", "create_idempotency_key"}))
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Folder folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_file_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private File targetFile;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "total_size_bytes", nullable = false)
    private long totalSizeBytes;

    @Column(name = "chunk_size_bytes", nullable = false)
    private int chunkSizeBytes;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * The file this session produced once it completed, or {@code null} while
     * the upload is still in progress. Set in the same transaction as the
     * {@link File}/{@link FileVersion} rows so a completed session always points
     * at a fully persisted result, and used to answer a repeated completion
     * idempotently.
     */
    @Column(name = "completed_file_id")
    private UUID completedFileId;

    /** The version produced by completion; {@code null} until the session completes. */
    @Column(name = "completed_version_number")
    private Integer completedVersionNumber;

    @jakarta.persistence.Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "create_idempotency_key", length = 200)
    private String createIdempotencyKey;

    @Column(name = "create_request_hash", length = 64)
    private String createRequestHash;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public UploadSession() {
    }

    public UploadSession(
            User owner,
            Folder folder,
            File targetFile,
            String filename,
            String contentType,
            long totalSizeBytes,
            int chunkSizeBytes,
            int totalChunks,
            UploadSessionStatus status,
            LocalDateTime expiresAt) {
        this.owner = owner;
        this.folder = folder;
        this.targetFile = targetFile;
        this.filename = filename;
        this.contentType = contentType;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = totalChunks;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public Folder getFolder() {
        return folder;
    }

    public File getTargetFile() {
        return targetFile;
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

    public UploadSessionStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public UUID getCompletedFileId() {
        return completedFileId;
    }

    public Integer getCompletedVersionNumber() {
        return completedVersionNumber;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getCreateIdempotencyKey() { return createIdempotencyKey; }

    public String getCreateRequestHash() { return createRequestHash; }

    public void setCreateIdempotency(String key, String requestHash) {
        this.createIdempotencyKey = key;
        this.createRequestHash = requestHash;
    }

    public void setStatus(UploadSessionStatus status) {
        this.status = status;
    }

    /** Refreshes the durable worker lease timestamp when cleanup is claimed. */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Records the file/version this session produced and moves it to
     * {@link UploadSessionStatus#COMPLETED}. Callers must only invoke this once
     * the file and its version are durably persisted in the same transaction.
     */
    public void markCompleted(UUID completedFileId, int completedVersionNumber) {
        this.status = UploadSessionStatus.COMPLETED;
        this.completedFileId = completedFileId;
        this.completedVersionNumber = completedVersionNumber;
        this.completedAt = LocalDateTime.now();
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
