package com.minidrive.mapper;

import com.minidrive.dto.folder.FolderResponse;
import com.minidrive.entity.Folder;

public final class FolderMapper {

    private FolderMapper() {
    }

    public static FolderResponse toResponse(Folder folder) {
        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getParentFolder() != null
                        ? folder.getParentFolder().getId()
                        : null,
                folder.getOwner().getId(),
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }
}
