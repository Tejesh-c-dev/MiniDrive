package com.minidrive.dto.file;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial update of file metadata. Fields left null are not changed.
 * A null folderId keeps the current folder; moving to root is not
 * expressed through null (Phase 4 upload flow may add explicit support).
 */
public class FileMetadataUpdateRequest {

    @Size(max = 255, message = "File name must be at most 255 characters")
    private String name;

    private UUID folderId;

    @Size(max = 255, message = "Content type must be at most 255 characters")
    private String contentType;

    @PositiveOrZero(message = "Size in bytes cannot be negative")
    private Long sizeBytes;

    @Size(max = 128, message = "Checksum must be at most 128 characters")
    private String checksum;

    public FileMetadataUpdateRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
}
