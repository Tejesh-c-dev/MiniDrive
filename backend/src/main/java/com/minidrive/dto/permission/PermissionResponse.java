package com.minidrive.dto.permission;

import com.minidrive.entity.FileRole;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single collaborator's direct permission on a file. Exposes only the
 * minimum user information needed by clients.
 */
public class PermissionResponse {

    private UUID userId;
    private String email;
    private FileRole role;
    private LocalDateTime createdAt;

    public PermissionResponse(
            UUID userId,
            String email,
            FileRole role,
            LocalDateTime createdAt) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public FileRole getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
