package com.minidrive.repository;

import com.minidrive.entity.Folder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findByOwnerIdAndParentFolderIdIsNull(UUID ownerId);

    List<Folder> findByOwnerIdAndParentFolderId(UUID ownerId, UUID parentFolderId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndParentFolderIdIsNullAndName(UUID ownerId, String name);

    boolean existsByOwnerIdAndParentFolderIdAndName(UUID ownerId, UUID parentFolderId, String name);

    @Query(value = "WITH RECURSIVE ancestors(id, parent_folder_id, deleting) AS (SELECT id, parent_folder_id, deleting FROM folders WHERE id = :folderId UNION ALL SELECT f.id, f.parent_folder_id, f.deleting FROM folders f JOIN ancestors a ON f.id = a.parent_folder_id) SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM ancestors WHERE deleting = TRUE", nativeQuery = true)
    boolean hasDeletingAncestor(@Param("folderId") UUID folderId);
}
