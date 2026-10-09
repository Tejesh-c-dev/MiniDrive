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
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Direct, file-scoped permission granted to a registered user. Ownership is not
 * represented here: {@link File#getOwner()} remains authoritative, so no
 * {@code OWNER} row is ever stored. Each {@code (file_id, user_id)} pair is
 * unique and grants exactly one role of {@link FileRole#EDITOR} or
 * {@link FileRole#VIEWER}.
 */
@Entity
@Table(
        name = "file_permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_file_permissions_file_user",
                columnNames = {"file_id", "user_id"}))
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private File file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Permission() {
    }

    public Permission(File file, User user, FileRole role) {
        this.file = file;
        this.user = user;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public File getFile() {
        return file;
    }

    public User getUser() {
        return user;
    }

    public FileRole getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setRole(FileRole role) {
        this.role = role;
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
