package com.minidrive.mapper;

import com.minidrive.dto.file.FileVersionResponse;
import com.minidrive.entity.FileVersion;
import com.minidrive.entity.User;

/**
 * Maps an immutable {@link FileVersion} to its public read model. The storage
 * object key is intentionally never copied into the response.
 */
public final class FileVersionMapper {

    private FileVersionMapper() {
    }

    public static FileVersionResponse toResponse(FileVersion version) {
        User creator = version.getCreatedBy();

        return new FileVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getOriginalFilename(),
                version.getContentType(),
                version.getSizeBytes(),
                creator != null ? creator.getId() : null,
                creator != null ? creator.getName() : null,
                creator != null ? creator.getEmail() : null,
                version.getCreatedAt());
    }
}
