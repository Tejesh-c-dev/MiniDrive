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
 * An immutable snapshot of a logical file's content.
 *
 * <p>{@link File} is the mutable, current record: {@link File#getObjectKey()}
 * always points at the latest version so existing downloads and share links
 * keep returning current content. A {@code FileVersion} is append-only and
 * never mutated after creation, so historical bytes can always be traced back
 * to the exact storage object that held them.</p>
 *
 * <p>Version numbers start at 1 for a file's first stored content and increase
 * by one on every successful content replacement. The
 * {@code (file_id, version_number)} pair is unique, which makes the numbering
 * idempotent under retries and prevents two writers from claiming the same
 * version.</p>
 */
@Entity
@Table(
        name = "file_versions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_file_versions_file_version",
                        columnNames = {"file_id", "version_number"}),
                @UniqueConstraint(
                        name = "uq_file_versions_object_key",
                        columnNames = "object_key")
        })
public class FileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private File file;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /**
     * Storage object holding this version's bytes. Immutable: a later version
     * is written to a new key, so this key never gets overwritten.
     */
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** The user who uploaded this version, which may differ from the file owner. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public FileVersion() {
    }

    public FileVersion(
            File file,
            int versionNumber,
            String objectKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            User createdBy) {
        this.file = file;
        this.versionNumber = versionNumber;
        this.objectKey = objectKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getObjectKey() {
        return objectKey;
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

    public User getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
