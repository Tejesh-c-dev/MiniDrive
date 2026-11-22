package com.minidrive.service;

import com.minidrive.entity.RevokedToken;
import com.minidrive.repository.RevokedTokenRepository;
import com.minidrive.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Durable per-token logout and bounded cleanup of expired revocation records. */
@Service
public class TokenRevocationService {
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final RevokedTokenRepository revokedTokens;

    public TokenRevocationService(RevokedTokenRepository revokedTokens) {
        this.revokedTokens = revokedTokens;
    }

    public boolean isRevoked(String tokenId) {
        // Persistence errors intentionally propagate. The JWT filter leaves the
        // request unauthenticated on failure, so protected routes fail closed.
        return revokedTokens.existsById(tokenId);
    }

    @Transactional
    public void revoke(JwtService.ValidatedToken token) {
        if (!revokedTokens.existsById(token.tokenId())) {
            revokedTokens.saveAndFlush(new RevokedToken(token.tokenId(), token.expiresAt()));
        }
    }

    /** Delete at most one bounded batch; expiration validation uses zero clock skew. */
    @Scheduled(fixedDelayString = "${minidrive.token-revocation.cleanup-interval-ms:3600000}",
            initialDelayString = "${minidrive.token-revocation.cleanup-initial-delay-ms:3600000}")
    @Transactional
    public int cleanupExpiredBatch() {
        try {
            List<RevokedToken> expired = revokedTokens.findByExpiresAtLessThanEqual(
                    Instant.now(), PageRequest.of(0, CLEANUP_BATCH_SIZE));
            revokedTokens.deleteAll(expired);
            return expired.size();
        } catch (RuntimeException failure) {
            // Cleanup is maintenance only; a failed pass leaves records in place
            // and cannot weaken the request-time revocation check.
            log.warn("Expired JWT revocation cleanup failed; records will be retained for a later pass", failure);
            return 0;
        }
    }
}
