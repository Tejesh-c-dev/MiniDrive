package com.minidrive.repository;

import com.minidrive.entity.RevokedToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
    List<RevokedToken> findByExpiresAtLessThanEqual(Instant cutoff, Pageable pageable);
}
