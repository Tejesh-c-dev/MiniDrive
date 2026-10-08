package com.minidrive.repository;

import com.minidrive.entity.File;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRepository extends JpaRepository<File, UUID> {

    List<File> findByOwnerId(UUID ownerId);

    List<File> findByOwnerIdAndFolderId(UUID ownerId, UUID folderId);

    List<File> findByOwnerIdAndFolderIdIsNull(UUID ownerId);

    Optional<File> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndFolderIdIsNullAndName(UUID ownerId, String name);

    boolean existsByOwnerIdAndFolderIdAndName(UUID ownerId, UUID folderId, String name);
}
