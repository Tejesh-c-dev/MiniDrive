package com.minidrive.dto.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class FolderRequest {

    @NotBlank(message = "Folder name is required")
    @Size(max = 255, message = "Folder name must be at most 255 characters")
    private String name;

    private UUID parentFolderId;

    public FolderRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentFolderId() {
        return parentFolderId;
    }

    public void setParentFolderId(UUID parentFolderId) {
        this.parentFolderId = parentFolderId;
    }
}
