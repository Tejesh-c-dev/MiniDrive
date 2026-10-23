package com.minidrive.service;

import com.minidrive.entity.*;
import com.minidrive.dto.folder.FolderRequest;
import com.minidrive.repository.*;
import com.minidrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** H2-backed database lifecycle checks with observable object storage state. */
@SpringBootTest(properties = "minidrive.cleanup-journal.directory=target/cleanup-journal-test")
class FolderDeletionLifecycleIntegrationTest {
    @Autowired private FolderService folderService;
    @Autowired private FileService fileService;
    @Autowired private FolderDeletionLifecycle lifecycle;
    @Autowired private CleanupIntentJournal cleanupJournal;
    @Autowired private UserRepository users;
    @Autowired private FolderRepository folders;
    @Autowired private FileRepository files;
    @Autowired private FileVersionRepository versions;
    @Autowired private UploadSessionRepository sessions;
    @Autowired private UploadChunkRepository chunks;
    @Autowired private FolderDeletionJobRepository jobs;
    @Autowired private FolderDeletionObjectRepository objects;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FileListingCache listingCache;
    @MockitoBean private StorageService storage;

    private final Map<String, byte[]> bucket = new ConcurrentHashMap<>();
    private User owner;
    private Authentication auth;

    @BeforeEach void reset() {
        for (CleanupIntentJournal.Intent intent : cleanupJournal.pending()) cleanupJournal.resolve(intent);
        jdbc.execute("DROP TABLE IF EXISTS folder_delete_guard");
        jdbc.execute("DROP TABLE IF EXISTS file_delete_guard");
        jdbc.execute("DROP TABLE IF EXISTS file_delete_finalize_guard");
        jdbc.execute("ALTER TABLE file_versions DROP CONSTRAINT IF EXISTS ck_test_upload_version_failure");
        objects.deleteAll(); jobs.deleteAll();
        chunks.deleteAll(); sessions.deleteAll(); versions.deleteAll(); files.deleteAll(); folders.deleteAll(); users.deleteAll();
        bucket.clear(); org.mockito.Mockito.reset(storage);
        doAnswer(inv -> { bucket.put(inv.getArgument(0), new byte[]{1}); return null; })
                .when(storage).upload(anyString(), any(), anyLong(), anyString());
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (bucket.remove(key) == null) return null; // Silo remove is idempotent for missing keys.
            return null;
        }).when(storage).delete(anyString());
        owner = users.saveAndFlush(new User("Owner", "deletion@test.local", "hash"));
        auth = new UsernamePasswordAuthenticationToken(owner.getEmail(), "");
    }

    @Test void emptyFolderFinalizesImmediatelyAndRepeatedStorageDeleteIsSafe() {
        Folder root = folder("empty", null);
        assertTrue(folderService.delete(auth, root.getId()).complete());
        assertFalse(folders.existsById(root.getId()));
        assertEquals(0, jobs.count());
        assertThrows(RuntimeException.class, () -> folderService.delete(auth, root.getId()));
    }

    @Test void missingStoredObjectIsIdempotentAndDoesNotBlockMetadataFinalization() {
        Folder root = folder("root-with-missing-object", null);
        storedFile(root, "already-missing", "already-missing");

        assertTrue(folderService.delete(auth, root.getId()).complete());
        verify(storage).delete("already-missing");
        assertFalse(folders.existsById(root.getId()));
        assertEquals(0, jobs.count());
    }

    @Test void nestedFilesVersionsAndStagedChunksAreManifestedAndRemoved() {
        Folder root = folder("root", null);
        Folder nested = folder("nested", root);
        Folder deeper = folder("deeper", nested);
        File file = storedFile(deeper, "current-key", "current-key", "old-key");
        UploadSession session = sessions.saveAndFlush(new UploadSession(owner, deeper, null, "pending.bin",
                "application/octet-stream", 1, 5, 1, UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(2)));
        chunks.saveAndFlush(new UploadChunk(session, 0, 1, "a", "_uploads/owner/session/0"));
        bucket.put("current-key", new byte[]{1}); bucket.put("old-key", new byte[]{1});
        bucket.put("_uploads/owner/session/0", new byte[]{1});

        assertTrue(folderService.delete(auth, root.getId()).complete());
        assertTrue(bucket.isEmpty());
        assertFalse(folders.existsById(root.getId()));
        assertFalse(files.existsById(file.getId()));
        assertFalse(sessions.existsById(session.getId()));
        verify(storage, times(1)).delete("current-key");
        verify(storage).delete("old-key");
        verify(storage).delete("_uploads/owner/session/0");
    }

    @Test void storageOutageKeepsManifestAndMetadataThenRetryAfterRestartCompletes() {
        Folder root = folder("root", null);
        Folder child = folder("child", root);
        File file = storedFile(child, "key-current", "key-current", "key-old");
        bucket.put("key-current", new byte[]{1}); bucket.put("key-old", new byte[]{1});
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (unavailable.get() && key.equals("key-old")) throw new IllegalStateException("storage outage");
            bucket.remove(key); return null;
        }).when(storage).delete(anyString());

        assertFalse(folderService.delete(auth, root.getId()).complete());
        FolderDeletionJob job = jobs.findByRootFolderId(root.getId()).orElseThrow();
        assertEquals("RETRY", job.getStatus());
        assertTrue(folders.existsById(root.getId()));
        assertTrue(files.existsById(file.getId()));
        assertTrue(objects.findByJobIdAndDeletedFalseOrderById(job.getId()).size() >= 1);
        assertTrue(bucket.containsKey("key-old"));
        FolderService.DeletionResult duplicate = folderService.delete(auth, root.getId());
        assertFalse(duplicate.complete());
        assertEquals(job.getId(), duplicate.jobId());
        assertThrows(com.minidrive.exception.FolderDeletionInProgressException.class, () -> {
            FolderRequest request = new FolderRequest();
            request.setName("new-child"); request.setParentFolderId(root.getId());
            folderService.create(auth, request);
        });

        unavailable.set(false);
        assertTrue(lifecycle.attempt(job.getId())); // same durable job, as after worker restart
        assertTrue(bucket.isEmpty());
        assertFalse(folders.existsById(root.getId()));
        assertEquals(0, jobs.count());
    }

    @Test void failureAfterPartialProgressRetainsOnlyRemainingKeyAndRetriesIdempotently() {
        Folder root = folder("root", null);
        File file = storedFile(root, "a-current", "a-current", "b-old");
        bucket.put("a-current", new byte[]{1}); bucket.put("b-old", new byte[]{1});
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (fail.get() && key.equals("b-old")) throw new IllegalStateException("injected failure");
            bucket.remove(key); return null;
        }).when(storage).delete(anyString());

        assertFalse(folderService.delete(auth, root.getId()).complete());
        FolderDeletionJob job = jobs.findByRootFolderId(root.getId()).orElseThrow();
        assertTrue(objects.findByJobIdAndDeletedFalseOrderById(job.getId()).size() >= 1);
        assertTrue(files.existsById(file.getId()));
        fail.set(false);
        assertTrue(lifecycle.attempt(job.getId()));
        assertTrue(bucket.isEmpty());
        assertFalse(files.existsById(file.getId()));
    }

    @Test void finalDatabaseFailureAfterObjectCleanupKeepsJobAndManifestForRecovery() {
        Folder root = folder("root", null);
        File file = storedFile(root, "final-key", "final-key");
        bucket.put("final-key", new byte[]{1});
        jdbc.execute("CREATE TABLE folder_delete_guard(folder_id UUID NOT NULL REFERENCES folders(id))");
        jdbc.update("INSERT INTO folder_delete_guard(folder_id) VALUES (?)", root.getId());

        assertFalse(folderService.delete(auth, root.getId()).complete());
        FolderDeletionJob job = jobs.findByRootFolderId(root.getId()).orElseThrow();
        assertTrue(bucket.isEmpty());
        assertTrue(files.existsById(file.getId()));
        assertEquals(1, objects.findByJobId(job.getId()).size());
        assertTrue(objects.findByJobIdAndDeletedFalseOrderById(job.getId()).isEmpty());

        jdbc.execute("DROP TABLE folder_delete_guard");
        assertTrue(lifecycle.attempt(job.getId()));
        assertFalse(folders.existsById(root.getId()));
        assertEquals(0, jobs.count());
    }

    @Test void deletionWaitsForAnUploadAlreadyWritingInsideTheSubtree() throws Exception {
        Folder root = folder("root", null);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch allowUpload = new CountDownLatch(1);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            bucket.put(key, new byte[]{1});
            uploadStarted.countDown();
            assertTrue(allowUpload.await(5, TimeUnit.SECONDS));
            return null;
        }).when(storage).upload(anyString(), any(), anyLong(), anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> fileService.upload(auth,
                    new MockMultipartFile("file", "in-flight.txt", "text/plain", new byte[]{1}), root.getId()));
            assertTrue(uploadStarted.await(5, TimeUnit.SECONDS));
            var deletion = pool.submit(() -> folderService.delete(auth, root.getId()));
            Thread.sleep(100);
            assertFalse(deletion.isDone(), "folder deletion must wait for the owner-locked upload transaction");

            allowUpload.countDown();
            upload.get(5, TimeUnit.SECONDS);
            assertTrue(deletion.get(5, TimeUnit.SECONDS).complete());
            assertTrue(bucket.isEmpty());
            assertFalse(folders.existsById(root.getId()));
            assertEquals(0, files.count());
        } finally {
            allowUpload.countDown();
            pool.shutdownNow();
        }
    }

    @Test void failedUploadCompensationIsRecordedAsStandaloneRetryWork() {
        Folder root = folder("root", null);
        jdbc.execute("ALTER TABLE file_versions ADD CONSTRAINT ck_test_upload_version_failure CHECK (version_number <> 1)");
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(inv -> { bucket.put(inv.getArgument(0), new byte[]{1}); return null; })
                .when(storage).upload(anyString(), any(), anyLong(), anyString());
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (unavailable.get()) throw new IllegalStateException("storage outage during compensation");
            bucket.remove(key); return null;
        }).when(storage).delete(anyString());

        assertThrows(RuntimeException.class, () -> fileService.upload(auth,
                new MockMultipartFile("file", "will-fail.txt", "text/plain", new byte[]{1}), root.getId()));
        assertEquals(0, files.count());
        assertEquals(1, jobs.count());
        FolderDeletionJob orphanJob = jobs.findAll().get(0);
        assertNull(orphanJob.getRootFolderId());
        assertEquals(1, objects.findByJobId(orphanJob.getId()).size());
        assertEquals(1, bucket.size());

        unavailable.set(false);
        assertTrue(lifecycle.attempt(orphanJob.getId()));
        assertTrue(bucket.isEmpty());
        assertEquals(0, jobs.count());
    }

    @Test void failedFallbackJobInsertIsJournaledAndReplayedAfterRestart() {
        jdbc.execute("ALTER TABLE file_versions ADD CONSTRAINT ck_test_upload_version_failure CHECK (version_number <> 1)");
        jdbc.execute("ALTER TABLE folder_deletion_jobs ADD CONSTRAINT ck_test_no_standalone_job CHECK (root_folder_id IS NOT NULL)");
        doAnswer(inv -> { bucket.put(inv.getArgument(0), new byte[]{1}); return null; })
                .when(storage).upload(anyString(), any(), anyLong(), anyString());
        doAnswer(inv -> { throw new IllegalStateException("object store unavailable during compensation"); })
                .when(storage).delete(anyString());

        assertThrows(RuntimeException.class, () -> fileService.upload(auth,
                new MockMultipartFile("file", "journaled.txt", "text/plain", new byte[]{1}), null));

        String orphanKey = bucket.keySet().iterator().next();
        assertTrue(cleanupJournal.pending().stream().anyMatch(intent -> intent.objectKey().equals(orphanKey)));
        assertEquals(0, jobs.count(), "PostgreSQL cleanup-job persistence was unavailable");

        // A new journal instance represents process restart and reads the fsynced intent from disk.
        CleanupIntentJournal restarted = new CleanupIntentJournal("target/cleanup-journal-test");
        assertTrue(restarted.pending().stream().anyMatch(intent -> intent.objectKey().equals(orphanKey)));

        jdbc.execute("ALTER TABLE folder_deletion_jobs DROP CONSTRAINT ck_test_no_standalone_job");
        jdbc.execute("ALTER TABLE file_versions DROP CONSTRAINT ck_test_upload_version_failure");
        doAnswer(inv -> { bucket.remove(inv.getArgument(0)); return null; }).when(storage).delete(anyString());
        lifecycle.replayCleanupJournal();
        lifecycle.replayCleanupJournal();

        assertFalse(bucket.containsKey(orphanKey));
        assertFalse(cleanupJournal.pending().stream().anyMatch(intent -> intent.objectKey().equals(orphanKey)));
        assertEquals(0, files.count());
    }

    @Test void journalReplayPreservesCommittedCurrentAndHistoricalObjects() {
        File file = storedFile(null, "committed-current", "committed-current", "committed-history");
        bucket.put("committed-current", new byte[]{1});
        bucket.put("committed-history", new byte[]{2});
        cleanupJournal.record("committed-current");
        cleanupJournal.record("committed-history");

        lifecycle.replayCleanupJournal();

        assertTrue(bucket.containsKey("committed-current"));
        assertTrue(bucket.containsKey("committed-history"));
        assertTrue(files.existsById(file.getId()));
        assertTrue(cleanupJournal.pending().isEmpty());
        verify(storage, never()).delete("committed-current");
        verify(storage, never()).delete("committed-history");
    }

    @Test void fileDeleteCommitsManifestBeforeStorageAndRetainsHistoricalObjects() {
        File file = storedFile(null, "file-current", "file-current", "file-history");
        bucket.put("file-current", new byte[]{1});
        bucket.put("file-history", new byte[]{2});

        fileService.delete(auth, file.getId());

        assertFalse(files.existsById(file.getId()));
        assertEquals(0, versions.count());
        assertFalse(bucket.containsKey("file-current"));
        assertTrue(bucket.containsKey("file-history"), "historical objects remain retained by policy");
        assertEquals(0, jobs.count());
    }

    @Test void storageFailureLeavesDurableManifestAndRestartedWorkerFinishesCleanup() {
        File file = storedFile(null, "pending-current", "pending-current", "pending-history");
        bucket.put("pending-current", new byte[]{1});
        bucket.put("pending-history", new byte[]{2});
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (unavailable.get()) throw new IllegalStateException("storage outage");
            bucket.remove(key);
            return null;
        }).when(storage).delete(anyString());

        fileService.delete(auth, file.getId());

        assertFalse(files.existsById(file.getId()));
        assertTrue(bucket.containsKey("pending-current"));
        FolderDeletionJob job = jobs.findAll().get(0);
        assertEquals(List.of("pending-current"), objects.findByJobId(job.getId()).stream()
                .map(FolderDeletionObject::getObjectKey).toList());
        assertThrows(RuntimeException.class, () -> fileService.download(auth, file.getId()));
        var update = new com.minidrive.dto.file.FileMetadataUpdateRequest();
        update.setName("must-not-return.txt");
        assertThrows(RuntimeException.class, () -> fileService.update(auth, file.getId(), update));
        assertThrows(RuntimeException.class, () -> fileService.delete(auth, file.getId()));

        unavailable.set(false);
        FolderDeletionLifecycle restarted = new FolderDeletionLifecycle(folders, files, versions, sessions,
                chunks, users, jobs, objects, storage, listingCache, transactionManager, cleanupJournal);
        assertTrue(restarted.attempt(job.getId()));
        assertFalse(bucket.containsKey("pending-current"));
        assertTrue(bucket.containsKey("pending-history"));
        assertEquals(0, jobs.count());
    }

    @Test void failedMetadataTransactionDoesNotDeleteObjectAndRetryConverges() {
        File file = storedFile(null, "transaction-failure-current", "transaction-failure-current");
        bucket.put("transaction-failure-current", new byte[]{1});
        jdbc.execute("CREATE TABLE file_delete_guard(file_id UUID NOT NULL REFERENCES files(id))");
        jdbc.update("INSERT INTO file_delete_guard(file_id) VALUES (?)", file.getId());

        assertThrows(RuntimeException.class, () -> fileService.delete(auth, file.getId()));
        assertTrue(files.existsById(file.getId()));
        assertTrue(bucket.containsKey("transaction-failure-current"),
                "storage must not be called until the metadata and manifest transaction commits");
        assertEquals(0, jobs.count());
        verify(storage, never()).delete("transaction-failure-current");

        jdbc.execute("DROP TABLE file_delete_guard");
        fileService.delete(auth, file.getId());
        assertFalse(files.existsById(file.getId()));
        assertFalse(bucket.containsKey("transaction-failure-current"));
    }

    @Test void successfulStorageDeleteThenFailedFinalizationRemainsRetryableAfterRestart() {
        File file = storedFile(null, "finalize-failure-current", "finalize-failure-current");
        bucket.put("finalize-failure-current", new byte[]{1});
        jdbc.execute("CREATE TABLE file_delete_finalize_guard(job_id UUID NOT NULL REFERENCES folder_deletion_jobs(id))");
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            bucket.remove(key);
            UUID jobId = jdbc.queryForObject("SELECT id FROM folder_deletion_jobs", UUID.class);
            jdbc.update("INSERT INTO file_delete_finalize_guard(job_id) VALUES (?)", jobId);
            return null;
        }).when(storage).delete("finalize-failure-current");

        fileService.delete(auth, file.getId());

        assertFalse(files.existsById(file.getId()));
        assertFalse(bucket.containsKey("finalize-failure-current"));
        FolderDeletionJob job = jobs.findAll().get(0);
        assertTrue(objects.findByJobIdAndDeletedFalseOrderById(job.getId()).isEmpty(),
                "physical success is durably recorded even though job finalization failed");
        assertTrue(jobs.existsById(job.getId()));

        jdbc.execute("DROP TABLE file_delete_finalize_guard");
        FolderDeletionLifecycle restarted = new FolderDeletionLifecycle(folders, files, versions, sessions,
                chunks, users, jobs, objects, storage, listingCache, transactionManager, cleanupJournal);
        assertTrue(restarted.attempt(job.getId()));
        assertEquals(0, jobs.count());
        assertFalse(bucket.containsKey("finalize-failure-current"));
    }

    @Test void unauthorizedDeleteDoesNotCreateCleanupWork() {
        File file = storedFile(null, "unauthorized-current", "unauthorized-current");
        bucket.put("unauthorized-current", new byte[]{1});
        User stranger = users.saveAndFlush(new User("Stranger", "stranger-" + UUID.randomUUID() + "@test.local", "hash"));
        Authentication strangerAuth = new UsernamePasswordAuthenticationToken(stranger.getEmail(), "");

        assertThrows(RuntimeException.class, () -> fileService.delete(strangerAuth, file.getId()));
        assertTrue(files.existsById(file.getId()));
        assertTrue(bucket.containsKey("unauthorized-current"));
        assertEquals(0, jobs.count());
    }

    @Test void journalReplayRetriesPartialDeletesAndToleratesAlreadyMissingObjects() {
        bucket.put("journal-partial-good", new byte[]{1});
        bucket.put("journal-partial-retry", new byte[]{2});
        cleanupJournal.record("journal-partial-good");
        cleanupJournal.record("journal-partial-retry");
        cleanupJournal.record("journal-already-absent");
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.equals("journal-partial-retry")) throw new IllegalStateException("temporary storage outage");
            bucket.remove(key); return null;
        }).when(storage).delete(anyString());

        lifecycle.replayCleanupJournal();
        assertFalse(bucket.containsKey("journal-partial-good"));
        assertTrue(bucket.containsKey("journal-partial-retry"));
        assertTrue(cleanupJournal.pending().stream().anyMatch(i -> i.objectKey().equals("journal-partial-retry")));
        assertFalse(cleanupJournal.pending().stream().anyMatch(i -> i.objectKey().equals("journal-partial-good")));

        doAnswer(inv -> { bucket.remove(inv.getArgument(0)); return null; }).when(storage).delete(anyString());
        lifecycle.replayCleanupJournal();
        assertTrue(cleanupJournal.pending().isEmpty());
        assertTrue(bucket.isEmpty());
    }

    @Test void multipleReplayWorkersConvergeOnTheSameIntent() throws Exception {
        bucket.put("journal-two-workers", new byte[]{3});
        cleanupJournal.record("journal-two-workers");
        doAnswer(inv -> { bucket.remove(inv.getArgument(0)); return null; }).when(storage).delete(anyString());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(lifecycle::replayCleanupJournal);
            var second = workers.submit(lifecycle::replayCleanupJournal);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertFalse(bucket.containsKey("journal-two-workers"));
            assertTrue(cleanupJournal.pending().isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void replayWaitsForActiveUploadAndPreservesItsCommittedObject() throws Exception {
        CountDownLatch uploadStored = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> key = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            String objectKey = inv.getArgument(0);
            key.set(objectKey);
            bucket.put(objectKey, new byte[]{7});
            uploadStored.countDown();
            assertTrue(finishUpload.await(5, TimeUnit.SECONDS));
            return null;
        }).when(storage).upload(anyString(), any(), anyLong(), anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> fileService.upload(auth,
                    new MockMultipartFile("file", "active.txt", "text/plain", new byte[]{7}), null));
            assertTrue(uploadStored.await(5, TimeUnit.SECONDS));
            var journalRecord = pool.submit(() -> cleanupJournal.record(key.get()));
            Thread.sleep(100);
            assertFalse(journalRecord.isDone(), "journal write must coordinate with the active key writer");
            finishUpload.countDown();
            upload.get(5, TimeUnit.SECONDS);
            journalRecord.get(5, TimeUnit.SECONDS);

            lifecycle.replayCleanupJournal();
            assertTrue(bucket.containsKey(key.get()));
            assertTrue(files.existsByObjectKey(key.get()));
            verify(storage, never()).delete(key.get());
        } finally {
            finishUpload.countDown();
            pool.shutdownNow();
        }
    }

    private Folder folder(String name, Folder parent) {
        return folders.saveAndFlush(new Folder(owner, parent, name));
    }

    private File storedFile(Folder folder, String current, String... versionKeys) {
        File file = new File(owner, folder, "file-" + UUID.randomUUID(), "application/octet-stream", 1, null);
        file.setObjectKey(current);
        file = files.saveAndFlush(file);
        int version = 1;
        for (String key : versionKeys)
            versions.saveAndFlush(new FileVersion(file, version++, key, "file", "application/octet-stream", 1, owner));
        return file;
    }
}
