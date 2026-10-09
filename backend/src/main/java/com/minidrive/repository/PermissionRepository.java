package com.minidrive.repository;

import com.minidrive.entity.Permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByFileIdAndUserId(UUID fileId, UUID userId);

    List<Permission> findByFileIdOrderByCreatedAtAsc(UUID fileId);

    boolean existsByFileIdAndUserId(UUID fileId, UUID userId);
}
