package com.minidrive.dto.file;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial update of user-editable file metadata. Fields left null are not changed.
 * A null folderId keeps the current folder; moving to root is not
 * expressed through null (Phase 4 upload flow may add explicit support).
 * Content-derived properties are tracked during JSON binding only so the
 * service can reject legacy requests atomically; they are never stored.
 */
public class FileMetadataUpdateRequest {

    @Size(max = 255, message = "File name must be at most 255 characters")
    private String name;

    private UUID folderId;

    private boolean contentMetadataUpdateRequested;

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

    @JsonAnySetter
    public void recordUnsupportedProperty(String propertyName, Object value) {
        if ("contentType".equals(propertyName)
                || "sizeBytes".equals(propertyName)
                || "checksum".equals(propertyName)) {
            contentMetadataUpdateRequested = true;
        }
    }

    public boolean hasContentMetadataUpdateRequested() {
        return contentMetadataUpdateRequested;
    }
}
