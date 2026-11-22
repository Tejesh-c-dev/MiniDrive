package com.minidrive.repository;

import com.minidrive.entity.FolderDeletionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface FolderDeletionJobRepository extends JpaRepository<FolderDeletionJob, UUID> {
    Optional<FolderDeletionJob> findByRootFolderId(UUID rootFolderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from FolderDeletionJob j where j.id = :id")
    Optional<FolderDeletionJob> findByIdForUpdate(@Param("id") UUID id);
    List<FolderDeletionJob> findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Collection<String> statuses, LocalDateTime now);
}
