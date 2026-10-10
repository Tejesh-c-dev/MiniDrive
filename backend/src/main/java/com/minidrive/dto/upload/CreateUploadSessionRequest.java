package com.minidrive.dto.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Declares a chunked upload before any bytes arrive. The server chooses the
 * chunk size and derives the chunk count from {@code totalSizeBytes}, so the
 * client never dictates how the object is split.
 */
public class CreateUploadSessionRequest {

    @NotBlank(message = "File name is required")
    @Size(max = 255, message = "File name must be at most 255 characters")
    private String filename;

    @NotBlank(message = "Content type is required")
    @Size(max = 255, message = "Content type must be at most 255 characters")
    private String contentType;

    @NotNull(message = "Size in bytes is required")
    @PositiveOrZero(message = "Size in bytes cannot be negative")
    private Long totalSizeBytes;

    /** Optional destination folder for a brand-new file. */
    private UUID folderId;

    /** Optional existing file whose content this upload will replace. */
    private UUID targetFileId;

    public CreateUploadSessionRequest() {
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public void setTotalSizeBytes(Long totalSizeBytes) {
        this.totalSizeBytes = totalSizeBytes;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public UUID getTargetFileId() {
        return targetFileId;
    }

    public void setTargetFileId(UUID targetFileId) {
        this.targetFileId = targetFileId;
    }
}
