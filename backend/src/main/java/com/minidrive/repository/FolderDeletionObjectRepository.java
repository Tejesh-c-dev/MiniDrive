package com.minidrive.repository;

import com.minidrive.entity.FolderDeletionObject;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FolderDeletionObjectRepository extends JpaRepository<FolderDeletionObject, UUID> {
    boolean existsByObjectKey(String objectKey);
    List<FolderDeletionObject> findByJobIdAndDeletedFalseOrderById(UUID jobId);
    List<FolderDeletionObject> findTop100ByJobIdAndDeletedFalseOrderById(UUID jobId);
    List<FolderDeletionObject> findByJobId(UUID jobId);
    boolean existsByJobIdAndDeletedFalse(UUID jobId);
}
