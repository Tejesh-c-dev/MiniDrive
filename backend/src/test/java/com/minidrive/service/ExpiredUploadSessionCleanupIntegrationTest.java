package com.minidrive.service;

import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.entity.User;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** H2 lifecycle tests; storage is mocked and these do not prove PostgreSQL locking or Silo behavior. */
@SpringBootTest(properties = {
        "minidrive.cleanup-journal.directory=target/upload-cleanup-test",
        "minidrive.upload-cleanup.batch-size=2",
        "minidrive.upload-cleanup.claim-lease-ms=60000"
})
class ExpiredUploadSessionCleanupIntegrationTest {
    @Autowired private ExpiredUploadSessionCleanup cleanup;
    @Autowired private UploadSessionRepository sessions;
    @Autowired private UploadChunkRepository chunks;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private StorageService storage;

    private User owner;
    private final List<String> deleted = new ArrayList<>();

    @BeforeEach void reset() {
        chunks.deleteAll(); sessions.deleteAll(); users.deleteAll();
        deleted.clear();
        org.mockito.Mockito.reset(storage);
        doAnswer(call -> { deleted.add(call.getArgument(0)); return null; }).when(storage).delete(anyString());
        owner = users.saveAndFlush(new User("Cleanup", "cleanup-" + UUID.randomUUID() + "@test.local", "hash"));
    }

    @Test void expiredManifestIsRemovedAndMissingObjectsAreIdempotent() {
        UploadSession session = session(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1));
        chunks.saveAndFlush(new UploadChunk(session, 0, 1, "hash", "_uploads/u/s/0"));

        assertEquals(1, cleanup.cleanupBatch());
        assertFalse(sessions.existsById(session.getId()));
        assertTrue(chunks.findBySessionIdOrderByChunkIndexAsc(session.getId()).isEmpty());
        verify(storage).delete("_uploads/u/s/0");
    }

    @Test void activeAndCompletedSessionsAreNotCleaned() {
        UploadSession active = session(UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(1));
        UploadSession completed = session(UploadSessionStatus.COMPLETED, LocalDateTime.now().minusDays(2));
        chunks.saveAndFlush(new UploadChunk(active, 0, 1, "hash", "active-key"));
        chunks.saveAndFlush(new UploadChunk(completed, 0, 1, "hash", "completed-key"));

        cleanup.cleanupBatch();

        assertTrue(sessions.existsById(active.getId()));
        assertTrue(sessions.existsById(completed.getId()));
        assertEquals(List.of(), deleted);
    }

    @Test void storageFailureRetainsManifestAndStaleClaimCanRetryAfterRestart() {
        UploadSession session = session(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1));
        chunks.saveAndFlush(new UploadChunk(session, 0, 1, "hash", "retry-key"));
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("retry-key");

        assertEquals(0, cleanup.cleanupBatch());
        assertTrue(sessions.findById(session.getId()).orElseThrow().getStatus() == UploadSessionStatus.CLEANING);
        assertEquals(1, chunks.findBySessionIdOrderByChunkIndexAsc(session.getId()).size());

        // Simulate process loss beyond the persisted claim lease, then a fresh worker pass.
        jdbc.update("update upload_sessions set updated_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(2), session.getId());
        org.mockito.Mockito.reset(storage);
        doAnswer(call -> { deleted.add(call.getArgument(0)); return null; }).when(storage).delete(anyString());
        assertEquals(1, cleanup.cleanupBatch());
        assertFalse(sessions.existsById(session.getId()));
        assertEquals(List.of("retry-key"), deleted);
    }

    @Test void selectionIsBoundedToConfiguredBatchSize() {
        List<UploadSession> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) created.add(session(UploadSessionStatus.INITIATED, LocalDateTime.now().minusHours(1)));
        assertEquals(2, cleanup.cleanupBatch());
        assertEquals(1, sessions.count());
    }

    private UploadSession session(UploadSessionStatus status, LocalDateTime expiry) {
        return sessions.saveAndFlush(new UploadSession(owner, null, null, "pending.bin",
                "application/octet-stream", 1, 5, 1, status, expiry));
    }
}
