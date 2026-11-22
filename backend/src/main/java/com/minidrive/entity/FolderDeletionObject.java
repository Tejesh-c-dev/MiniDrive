package com.minidrive.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "folder_deletion_objects", uniqueConstraints = @UniqueConstraint(name = "uq_folder_deletion_objects_job_key", columnNames = {"job_id", "object_key"}))
public class FolderDeletionObject {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "job_id", nullable = false) private FolderDeletionJob job;
    @Column(name = "object_key", nullable = false, length = 512) private String objectKey;
    @Column(nullable = false) private boolean deleted;
    protected FolderDeletionObject() {}
    public FolderDeletionObject(FolderDeletionJob job, String objectKey) { this.job = job; this.objectKey = objectKey; }
    public UUID getId() { return id; }
    public String getObjectKey() { return objectKey; }
    public boolean isDeleted() { return deleted; }
    public void markDeleted() { deleted = true; }
}
