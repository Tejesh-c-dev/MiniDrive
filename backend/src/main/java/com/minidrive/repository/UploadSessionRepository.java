package com.minidrive.repository;

import com.minidrive.entity.UploadSession;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    /**
     * Owner-scoped lookup used by every session endpoint so a session id
     * belonging to another user resolves to "not found" instead of leaking the
     * session's existence.
     */
    Optional<UploadSession> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<UploadSession> findByOwnerId(UUID ownerId);

    /** Sessions whose expiration has passed; used by expiry/cleanup queries. */
    List<UploadSession> findByExpiresAtBefore(LocalDateTime cutoff);
}
