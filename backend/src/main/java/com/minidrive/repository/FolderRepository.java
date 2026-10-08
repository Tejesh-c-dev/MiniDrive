package com.minidrive.repository;

import com.minidrive.entity.Folder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findByOwnerIdAndParentFolderIdIsNull(UUID ownerId);

    List<Folder> findByOwnerIdAndParentFolderId(UUID ownerId, UUID parentFolderId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndParentFolderIdIsNullAndName(UUID ownerId, String name);

    boolean existsByOwnerIdAndParentFolderIdAndName(UUID ownerId, UUID parentFolderId, String name);
}
