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

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * File metadata only. Actual object bytes are stored in object storage
 * (MinIO) in a later phase and are never loaded through this entity.
 */
@Entity
@Table(name = "files")
public class File {

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

    @Column(nullable = false)
    private String name;

    /**
     * Placeholder key for the future object storage integration (Phase 4).
     */
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public File() {
    }

    public File(
            User owner,
            Folder folder,
            String name,
            String contentType,
            long sizeBytes,
            String checksum) {
        this.owner = owner;
        this.folder = folder;
        this.name = name;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
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

    public String getName() {
        return name;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setFolder(Folder folder) {
        this.folder = folder;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
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
