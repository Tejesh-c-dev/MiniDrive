package com.minidrive.mapper;

import com.minidrive.dto.file.FileMetadataResponse;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.entity.File;

/**
 * Maps a {@link File} (the mutable "current" record) to its API read models.
 * {@code currentVersion} is supplied by the caller, which derives it from the
 * file's version history rather than storing a redundant column on the file.
 */
public final class FileMapper {

    private FileMapper() {
    }

    public static FileResponse toResponse(File file, int currentVersion) {
        return new FileResponse(
                file.getId(),
                file.getName(),
                file.getFolder() != null
                        ? file.getFolder().getId()
                        : null,
                file.getContentType(),
                file.getSizeBytes(),
                file.getUpdatedAt(),
                currentVersion);
    }

    public static FileMetadataResponse toMetadataResponse(File file, int currentVersion) {
        return new FileMetadataResponse(
                file.getId(),
                file.getName(),
                file.getFolder() != null
                        ? file.getFolder().getId()
                        : null,
                file.getOwner().getId(),
                file.getObjectKey(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getChecksum(),
                file.getCreatedAt(),
                file.getUpdatedAt(),
                currentVersion);
    }

    public static FileUploadResponse toUploadResponse(File file, int currentVersion) {
        return toUploadResponse(file, FileUploadResponse.UPLOAD_STATUS_PENDING, currentVersion);
    }

    public static FileUploadResponse toUploadResponse(
            File file,
            String uploadStatus,
            int currentVersion) {
        return new FileUploadResponse(
                file.getId(),
                file.getName(),
                file.getFolder() != null
                        ? file.getFolder().getId()
                        : null,
                file.getOwner().getId(),
                file.getObjectKey(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getChecksum(),
                uploadStatus,
                file.getCreatedAt(),
                file.getUpdatedAt(),
                currentVersion);
    }
}
