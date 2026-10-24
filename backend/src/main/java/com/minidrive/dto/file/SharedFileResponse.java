package com.minidrive.dto.file;

import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.User;
import java.time.LocalDateTime;
import java.util.UUID;

/** Safe metadata for a file directly shared with the authenticated user. */
public record SharedFileResponse(UUID id, String name, String contentType,
        long sizeBytes, LocalDateTime updatedAt, int currentVersion,
        String ownerName, FileRole role) {
    public static SharedFileResponse from(File file, User owner, FileRole role, int currentVersion) {
        return new SharedFileResponse(file.getId(), file.getName(), file.getContentType(),
                file.getSizeBytes(), file.getUpdatedAt(), currentVersion, owner.getName(), role);
    }
}
