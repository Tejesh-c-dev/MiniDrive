package com.minidrive.repository;

import com.minidrive.entity.Permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.minidrive.entity.User;
import com.minidrive.entity.File;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByFileIdAndUserId(UUID fileId, UUID userId);

    List<Permission> findByFileIdOrderByCreatedAtAsc(UUID fileId);

    boolean existsByFileIdAndUserId(UUID fileId, UUID userId);

    @Query("select p.file, p.role, p.file.owner from Permission p where p.user = :user order by p.file.updatedAt desc")
    List<Object[]> findSharedFilesForUser(@Param("user") User user);
}
