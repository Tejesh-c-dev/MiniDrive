package com.minidrive.repository;

import com.minidrive.entity.UploadSession;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Owner-scoped lookup that takes a row-level write lock, used to serialize
     * concurrent completion attempts for the same session. The second attempt
     * blocks until the first commits, then observes the session as
     * {@code COMPLETED} and returns the already-produced result instead of
     * creating a second file or version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadSession s where s.id = :id and s.owner.id = :ownerId")
    Optional<UploadSession> findByIdAndOwnerIdForUpdate(
            @Param("id") UUID id,
            @Param("ownerId") UUID ownerId);

    List<UploadSession> findByOwnerId(UUID ownerId);

    /** Sessions whose expiration has passed; used by expiry/cleanup queries. */
    List<UploadSession> findByExpiresAtBefore(LocalDateTime cutoff);
}
