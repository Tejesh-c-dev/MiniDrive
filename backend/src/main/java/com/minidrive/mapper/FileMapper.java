package com.minidrive.mapper;

import com.minidrive.dto.file.FileMetadataResponse;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.entity.File;

public final class FileMapper {

    private FileMapper() {
    }

    public static FileResponse toResponse(File file) {
        return new FileResponse(
                file.getId(),
                file.getName(),
                file.getFolder() != null
                        ? file.getFolder().getId()
                        : null,
                file.getContentType(),
                file.getSizeBytes(),
                file.getUpdatedAt());
    }

    public static FileMetadataResponse toMetadataResponse(File file) {
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
                file.getUpdatedAt());
    }

    public static FileUploadResponse toUploadResponse(File file) {
        return toUploadResponse(file, FileUploadResponse.UPLOAD_STATUS_PENDING);
    }

    public static FileUploadResponse toUploadResponse(File file, String uploadStatus) {
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
                file.getUpdatedAt());
    }
}
