package com.minidrive.dto.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to register file metadata. Actual binary upload arrives in
 * Phase 4 (MinIO); this only records metadata about a future object.
 */
public class FileMetadataRequest {

    @NotBlank(message = "File name is required")
    @Size(max = 255, message = "File name must be at most 255 characters")
    private String name;

    private UUID folderId;

    @NotBlank(message = "Content type is required")
    @Size(max = 255, message = "Content type must be at most 255 characters")
    private String contentType;

    @NotNull(message = "Size in bytes is required")
    @PositiveOrZero(message = "Size in bytes cannot be negative")
    private Long sizeBytes;

    @Size(max = 128, message = "Checksum must be at most 128 characters")
    private String checksum;

    public FileMetadataRequest() {
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
