package com.minidrive.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "folder_deletion_jobs")
public class FolderDeletionJob {
    @Id private UUID id;
    @Column(name = "root_folder_id", unique = true) private UUID rootFolderId;
    @Column(name = "owner_id") private UUID ownerId;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected FolderDeletionJob() {}
    public FolderDeletionJob(UUID id) {
        this.id = id; this.status = "PENDING"; this.nextAttemptAt = LocalDateTime.now(); this.createdAt = LocalDateTime.now();
    }
    public FolderDeletionJob(UUID id, UUID rootFolderId, UUID ownerId) {
        this.id = id; this.rootFolderId = rootFolderId; this.ownerId = ownerId;
        this.status = "PENDING"; this.nextAttemptAt = LocalDateTime.now(); this.createdAt = LocalDateTime.now();
    }
    public UUID getId() { return id; }
    public UUID getRootFolderId() { return rootFolderId; }
    public UUID getOwnerId() { return ownerId; }
    public String getStatus() { return status; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void started() { status = "PENDING"; lastError = null; }
    public void failed(Throwable error) {
        attemptCount++;
        long delay = Math.min(3600, 5L << Math.min(attemptCount, 9));
        nextAttemptAt = LocalDateTime.now().plusSeconds(delay);
        lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (lastError.length() > 1000) lastError = lastError.substring(0, 1000);
        status = "RETRY";
    }
}
