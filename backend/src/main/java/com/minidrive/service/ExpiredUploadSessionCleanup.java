package com.minidrive.service;

import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.storage.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Durable, bounded sweeper for abandoned resumable upload sessions. */
@Service
public class ExpiredUploadSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(ExpiredUploadSessionCleanup.class);

    private final UploadSessionRepository sessions;
    private final UploadChunkRepository chunks;
    private final StorageService storage;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final long claimLeaseMs;

    public ExpiredUploadSessionCleanup(UploadSessionRepository sessions, UploadChunkRepository chunks,
            StorageService storage, PlatformTransactionManager manager, MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${minidrive.upload-cleanup.batch-size:50}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${minidrive.upload-cleanup.claim-lease-ms:300000}") long claimLeaseMs) {
        this.sessions = sessions;
        this.chunks = chunks;
        this.storage = storage;
        this.tx = new TransactionTemplate(manager);
        this.metrics = metrics;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.claimLeaseMs = Math.max(60000, Math.min(claimLeaseMs, 30L * 24 * 60 * 60 * 1000));
    }

    @Scheduled(fixedDelayString = "${minidrive.upload-cleanup.interval-ms:60000}",
            initialDelayString = "${minidrive.upload-cleanup.initial-delay-ms:60000}")
    public void scheduledCleanup() {
        cleanupBatch();
    }

    /** Exposed for deterministic lifecycle tests and one bounded scheduler pass. */
    public int cleanupBatch() {
        List<UUID> candidates = tx.execute(status ->
                sessions.findCleanupCandidates(LocalDateTime.now(), LocalDateTime.now().minusNanos(claimLeaseMs * 1_000_000),
                        PageRequest.of(0, batchSize)));
        int processed = 0;
        for (UUID id : candidates) {
            if (cleanup(id)) processed++;
        }
        return processed;
    }

    private boolean cleanup(UUID id) {
        metrics.counter("minidrive_upload_session_cleanup_total", "outcome", "attempted").increment();
        List<UploadChunk> manifest = tx.execute(status -> {
            UploadSession session = sessions.findByIdForUpdate(id).orElse(null);
            if (session == null) return null;
            if (session.getStatus() == UploadSessionStatus.INITIATED) {
                if (session.getExpiresAt().isAfter(LocalDateTime.now())) return null;
                session.setStatus(UploadSessionStatus.EXPIRED);
                sessions.saveAndFlush(session);
            } else if (session.getStatus() != UploadSessionStatus.EXPIRED) {
                if (session.getStatus() != UploadSessionStatus.CLEANING
                        || session.getUpdatedAt().isAfter(LocalDateTime.now().minusNanos(claimLeaseMs * 1_000_000)))
                    return null;
            }
            session.setStatus(UploadSessionStatus.CLEANING);
            session.touch();
            sessions.saveAndFlush(session);
            return chunks.findBySessionIdOrderByChunkIndexAsc(id);
        });
        if (manifest == null) {
            metrics.counter("minidrive_upload_session_cleanup_total", "outcome", "deferred").increment();
            return false;
        }

        try {
            for (UploadChunk chunk : manifest) storage.delete(chunk.getStagingObjectKey());
            tx.executeWithoutResult(status -> {
                UploadSession session = sessions.findByIdForUpdate(id).orElse(null);
                if (session != null && session.getStatus() == UploadSessionStatus.CLEANING) {
                    chunks.deleteBySessionId(id);
                    sessions.delete(session);
                    sessions.flush();
                }
            });
            metrics.counter("minidrive_upload_session_cleanup_total", "outcome", "success").increment();
            log.info("Expired upload session cleanup completed sessionId={} stagedObjects={}", id, manifest.size());
            return true;
        } catch (RuntimeException failure) {
            metrics.counter("minidrive_upload_session_cleanup_total", "outcome", "failure").increment();
            log.warn("Expired upload session cleanup will retry sessionId={} stagedObjects={}", id, manifest.size(), failure);
            return false;
        }
    }
}
