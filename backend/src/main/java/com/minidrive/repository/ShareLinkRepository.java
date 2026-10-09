package com.minidrive.repository;

import com.minidrive.entity.ShareLink;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    Optional<ShareLink> findByTokenHash(String tokenHash);

    boolean existsByTokenHash(String tokenHash);

    List<ShareLink> findByFileIdOrderByCreatedAtAsc(UUID fileId);

    Optional<ShareLink> findByIdAndFileId(UUID id, UUID fileId);
}
