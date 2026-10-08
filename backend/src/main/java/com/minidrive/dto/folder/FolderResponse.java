package com.minidrive.dto.folder;

import java.time.LocalDateTime;
import java.util.UUID;

public class FolderResponse {

    private UUID id;
    private String name;
    private UUID parentFolderId;
    private UUID ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public FolderResponse(
            UUID id,
            String name,
            UUID parentFolderId,
            UUID ownerId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.parentFolderId = parentFolderId;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getParentFolderId() {
        return parentFolderId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
