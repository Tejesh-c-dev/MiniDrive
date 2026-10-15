package com.minidrive.repository;

import com.minidrive.entity.File;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRepository extends JpaRepository<File, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from File f where f.id = :id")
    Optional<File> findByIdForUpdate(@Param("id") UUID id);

    List<File> findByOwnerId(UUID ownerId);

    List<File> findByOwnerIdAndFolderId(UUID ownerId, UUID folderId);

    List<File> findByOwnerIdAndFolderIdIsNull(UUID ownerId);

    Optional<File> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<File> findByObjectKey(String objectKey);

    boolean existsByObjectKey(String objectKey);

    boolean existsByOwnerIdAndFolderIdIsNullAndName(UUID ownerId, String name);

    boolean existsByOwnerIdAndFolderIdAndName(UUID ownerId, UUID folderId, String name);
}
