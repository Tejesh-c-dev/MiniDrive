package com.minidrive.integration;

import com.minidrive.entity.*;
import com.minidrive.repository.*;
import com.minidrive.service.FolderDeletionLifecycle;
import com.minidrive.service.ExpiredUploadSessionCleanup;
import com.minidrive.storage.StorageService;
import com.minidrive.storage.StorageNodeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Opt-in live PostgreSQL and Silo tests for durable folder deletion boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.web-application-type=none",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "minidrive.storage.replication.enabled=false",
        "minidrive.upload-cleanup.interval-ms=3600000",
        "minidrive.upload-cleanup.initial-delay-ms=3600000",
        "minidrive.folder-deletion.retry-delay-ms=3600000"
})
@Import(FolderDeletionRealInfrastructureTest.LiveStorageProperties.class)
@EnabledIfSystemProperty(named = "minidrive.live-deletion", matches = "true")
class FolderDeletionRealInfrastructureTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "md-delete-" + RUN.substring(0, 16);
    private static final String JDBC = "jdbc:postgresql://127.0.0.1:55439/minidrive_it";
    private static final String ENDPOINT = "http://127.0.0.1:9200";
    private static final String ACCESS = "md_it_silo";
    private static final String SECRET = "md_it_silo_2026_disposable";

    @Autowired private FolderDeletionLifecycle lifecycle;
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
    @Autowired private ExpiredUploadSessionCleanup uploadCleanup;
    @Autowired private MeterRegistry metrics;
    @MockitoSpyBean private StorageService storage;
    private static MinioClient admin;

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "md_it");
        registry.add("spring.datasource.password", () -> "md_it_2026_disposable");
        registry.add("minio.endpoint", () -> ENDPOINT);
        registry.add("minio.access-key", () -> ACCESS);
        registry.add("minio.secret-key", () -> SECRET);
        registry.add("minio.bucket", () -> BUCKET);
        registry.add("minio.secondary.endpoint", () -> "http://127.0.0.1:9300");
        registry.add("minio.secondary.access-key", () -> ACCESS);
        registry.add("minio.secondary.secret-key", () -> SECRET);
        registry.add("minio.secondary.bucket", () -> "minidrive-it-secondary");
    }

    @org.junit.jupiter.api.BeforeAll
    static void createDisposableBucket() throws Exception {
        admin = MinioClient.builder().endpoint(ENDPOINT).credentials(ACCESS, SECRET).build();
        assertFalse(admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @org.junit.jupiter.api.AfterAll
    static void removeDisposableBucket() throws Exception {
        if (admin == null) return;
        for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build()))
            admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.get().objectName()).build());
        admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
    }

    @AfterEach
    void removeOnlyThisRunMetadata() {
        String emailPrefix = "md-live-delete-" + RUN + "-%";
        jdbc.update("delete from folder_deletion_objects where job_id in "
                + "(select id from folder_deletion_jobs where owner_id in "
                + "(select id from users where email like ?))", emailPrefix);
        jdbc.update("delete from folder_deletion_jobs where owner_id in "
                + "(select id from users where email like ?)", emailPrefix);
        jdbc.update("delete from users where email like ?", emailPrefix);
        String keyPrefix = "_md-live-delete/" + RUN + "/%";
        jdbc.update("delete from object_locations where object_key like ?", keyPrefix);
        jdbc.update("delete from object_replicas where object_key like ?", keyPrefix);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveStorageProperties {
        @Bean @Primary
        StorageNodeProperties isolatedSiloNode() {
            StorageNodeProperties properties = new StorageNodeProperties();
            StorageNodeProperties.Node node = new StorageNodeProperties.Node();
            node.setId("primary"); node.setEnabled(true); node.setEndpoint(ENDPOINT);
            node.setAccessKey(ACCESS); node.setSecretKey(SECRET); node.setBucket(BUCKET);
            properties.setNodes(List.of(node));
            return properties;
        }
    }

    @Test
    void recursiveRequestCommitsCompleteManifestBeforeDeleteAndRecoversFailures() throws Exception {
        assertIsolatedResources();
        TreeFixture f = tree("recursive");
        Set<String> expected = new LinkedHashSet<>(f.allKeys());
        assertEquals(1, jdbc.queryForObject("select count(*) from files where id=?", Integer.class, f.fileId()));
        assertEquals(2, versions.findByFileIdOrderByVersionNumberAsc(f.fileId()).size());
        assertEquals(2, chunks.findBySessionIdOrderByChunkIndexAsc(f.sessionId()).size());
        expected.forEach(this::assertExists);
        assertExists(f.unrelatedKey());

        UUID jobId = lifecycle.request(f.rootId(), f.ownerId());
        assertNotNull(jobId);
        assertEquals(expected, new HashSet<>(objects.findByJobId(jobId).stream().map(FolderDeletionObject::getObjectKey).toList()));
        assertTrue(folders.findById(f.rootId()).orElseThrow().isDeleting());
        expected.forEach(this::assertExists); // request commit precedes every physical delete

        AtomicBoolean failOnce = new AtomicBoolean();
        String failedKey = f.historyKeys().get(0);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (failedKey.equals(key) && failOnce.compareAndSet(false, true))
                throw new IllegalStateException("test Silo outage after request commit");
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());
        assertFalse(lifecycle.attempt(jobId));
        assertTrue(jobs.existsById(jobId));
        assertTrue(objects.existsByJobIdAndDeletedFalse(jobId));
        assertExists(failedKey);
        assertTrue(f.allKeys().stream().filter(key -> !key.equals(failedKey)).anyMatch(this::isMissing),
                "some real Silo deletes should have completed before the injected failure");
        doAnswer(invocation -> invocation.callRealMethod()).when(storage).delete(anyString());

        // Fail the later root metadata finalization after all objects have been removed.
        String fn = "md_delete_finalize_" + RUN.substring(0, 8);
        String trigger = fn + "_trg";
        jdbc.execute("create function " + fn + "() returns trigger language plpgsql as $$ begin "
                + "if old.id = '" + f.rootId() + "'::uuid then raise exception 'test finalization failure'; end if; "
                + "return old; end $$");
        jdbc.execute("create trigger " + trigger + " before delete on folders for each row execute function " + fn + "()");
        try {
            assertFalse(lifecycle.attempt(jobId));
            assertTrue(jobs.existsById(jobId));
            assertEquals(expected.size(), objects.findByJobId(jobId).size(),
                    "failed finalization must roll back manifest removal with the folder delete");
            assertTrue(objects.findByJobId(jobId).stream().allMatch(FolderDeletionObject::isDeleted));
            expected.forEach(this::assertMissing);
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on folders");
            jdbc.execute("drop function if exists " + fn + "()");
        }

        assertTrue(lifecycle.attempt(jobId));
        assertFalse(jobs.existsById(jobId));
        assertFalse(folders.existsById(f.rootId()));
        assertFalse(files.existsById(f.fileId()));
        assertFalse(sessions.existsById(f.sessionId()));
        assertTrue(versions.findByFileIdOrderByVersionNumberAsc(f.fileId()).isEmpty());
        assertNull(lifecycle.request(f.rootId(), f.ownerId()), "replayed deletion must not recreate retired content");
        expected.forEach(this::assertMissing); // repeated deletes are idempotent
        assertExists(f.unrelatedKey());
    }

    @Test
    void requestTransactionRollbackLeavesMetadataAndObjectsAndStartsNoDelete() throws Exception {
        assertIsolatedResources();
        TreeFixture f = tree("request-rollback");
        String fn = "md_delete_request_" + RUN.substring(8, 16);
        String trigger = fn + "_trg";
        jdbc.execute("create function " + fn + "() returns trigger language plpgsql as $$ begin "
                + "raise exception 'test request transaction failure'; end $$");
        jdbc.execute("create trigger " + trigger + " before insert on folder_deletion_objects for each row execute function " + fn + "()");
        try {
            assertThrows(RuntimeException.class, () -> lifecycle.request(f.rootId(), f.ownerId()));
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on folder_deletion_objects");
            jdbc.execute("drop function if exists " + fn + "()");
        }
        assertFalse(folders.findById(f.rootId()).orElseThrow().isDeleting());
        assertTrue(files.existsById(f.fileId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from folder_deletion_jobs where root_folder_id=?", Integer.class, f.rootId()));
        f.allKeys().forEach(this::assertExists);
    }

    @Test
    void postgresFailureAfterRemoteDeleteRollsBackMarkAndManifestRetriesSafely() throws Exception {
        assertIsolatedResources();
        TreeFixture f = tree("md004");
        UUID jobId = lifecycle.requestFile(f.fileId(), f.ownerId());
        assertEquals(new HashSet<>(f.fileKeys()), new HashSet<>(objects.findByJobId(jobId).stream()
                .map(FolderDeletionObject::getObjectKey).toList()));
        String selected = f.currentKey();
        String fn = "md004_fail_mark_" + RUN.substring(16, 24);
        String trigger = fn + "_trg";
        jdbc.execute("create function " + fn + "() returns trigger language plpgsql as $$ begin "
                + "if old.object_key = '" + selected + "' then raise exception 'test mark deleted failure'; end if; "
                + "return new; end $$");
        jdbc.execute("create trigger " + trigger + " before update on folder_deletion_objects for each row execute function " + fn + "()");
        try {
            assertFalse(lifecycle.attempt(jobId));
            assertMissing(selected); // Silo delete committed before PostgreSQL update failed.
            assertTrue(jobs.existsById(jobId));
            assertTrue(objects.findByJobId(jobId).stream().anyMatch(item ->
                    item.getObjectKey().equals(selected) && !item.isDeleted()),
                    "failed PostgreSQL transaction must roll back the deleted marker");
            assertEquals(f.fileKeys().size(), objects.findByJobId(jobId).size(),
                    "durable committed manifest is the recovery record");
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on folder_deletion_objects");
            jdbc.execute("drop function if exists " + fn + "()");
        }

        assertTrue(lifecycle.attempt(jobId));
        assertFalse(jobs.existsById(jobId));
        assertTrue(lifecycle.attempt(jobId), "replaying a completed job must remain a no-op");
        f.fileKeys().forEach(this::assertMissing);
        assertExists(f.unrelatedKey());
        assertTrue(files.existsById(f.unrelatedFileId()));
    }

    @Test
    void recursiveDeletionRacesExpiredUploadCleanupWithDuplicateDeletesAndRetry() throws Exception {
        assertIsolatedResources();
        TreeFixture f = tree("worker-race");
        UUID jobId = lifecycle.request(f.rootId(), f.ownerId());
        jdbc.update("update upload_sessions set expires_at=? where id=?",
                LocalDateTime.now().minusMinutes(1), f.sessionId());

        CountDownLatch folderEnteredStagingDelete = new CountDownLatch(1);
        CountDownLatch bothWorkersEnteredStagingDelete = new CountDownLatch(2);
        CountDownLatch releaseDuplicateDeletes = new CountDownLatch(1);
        AtomicInteger stagingCalls = new AtomicInteger();
        String sharedKey = f.stagingKeys().get(0);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (sharedKey.equals(key)) {
                int call = stagingCalls.incrementAndGet();
                bothWorkersEnteredStagingDelete.countDown();
                if (call == 1) folderEnteredStagingDelete.countDown();
                assertTrue(releaseDuplicateDeletes.await(20, TimeUnit.SECONDS));
                if (call == 1) throw new IllegalStateException("folder worker transient delete failure");
            }
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());

        ExpiredUploadSessionCleanup independentUploadWorker = new ExpiredUploadSessionCleanup(
                sessions, chunks, storage, transactionManager, metrics, 1, 300000);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> folderResult = workers.submit(() -> lifecycle.attempt(jobId));
            assertTrue(folderEnteredStagingDelete.await(15, TimeUnit.SECONDS));
            Future<Integer> uploadResult = workers.submit(independentUploadWorker::cleanupBatch);
            assertTrue(bothWorkersEnteredStagingDelete.await(15, TimeUnit.SECONDS));
            releaseDuplicateDeletes.countDown();
            assertFalse(folderResult.get(20, TimeUnit.SECONDS));
            assertEquals(1, uploadResult.get(20, TimeUnit.SECONDS));
        } finally {
            releaseDuplicateDeletes.countDown();
            workers.shutdownNow();
        }

        assertFalse(sessions.existsById(f.sessionId()), "upload worker finalized only its expired session");
        assertTrue(jobs.existsById(jobId), "folder failure remains recoverable");
        FolderDeletionObject stagedManifest = objects.findByJobId(jobId).stream()
                .filter(item -> item.getObjectKey().equals(sharedKey)).findFirst().orElseThrow();
        assertFalse(stagedManifest.isDeleted(), "upload worker must not finalize folder manifest state");
        assertMissing(sharedKey);
        doAnswer(invocation -> invocation.callRealMethod()).when(storage).delete(anyString());
        assertTrue(lifecycle.attempt(jobId), "folder retry treats the upload worker's delete as idempotent");
        assertFalse(jobs.existsById(jobId));
        f.allKeys().forEach(this::assertMissing);
        assertExists(f.unrelatedKey());
        assertTrue(files.existsById(f.unrelatedFileId()));
    }

    private TreeFixture tree(String label) throws Exception {
        String prefix = "_md-live-delete/" + RUN + "/" + label + "/";
        User owner = users.saveAndFlush(new User("Live deletion " + label,
                "md-live-delete-" + RUN + "-" + label + "@example.test", "unused"));
        Folder root = folders.saveAndFlush(new Folder(owner, null, "root-" + label));
        Folder nested = folders.saveAndFlush(new Folder(owner, root, "nested-" + label));
        Folder outside = folders.saveAndFlush(new Folder(owner, null, "outside-" + label));
        List<String> history = List.of(prefix + "history-1", prefix + "history-2");
        String current = prefix + "current";
        File file = new File(owner, nested, "current.txt", "text/plain", 1, "unused");
        file.setObjectKey(current);
        file = files.saveAndFlush(file);
        List<String> fileKeys = new ArrayList<>();
        fileKeys.add(current);
        fileKeys.addAll(history);
        for (int i = 0; i < history.size(); i++)
            versions.saveAndFlush(new FileVersion(file, i + 1, history.get(i), "history.txt", "text/plain", 1, owner));

        UploadSession session = sessions.saveAndFlush(new UploadSession(owner, nested, null, "staged.bin",
                "application/octet-stream", 2, 1, 2, UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(1)));
        List<String> staging = List.of(prefix + "stage-0", prefix + "stage-1");
        for (int i = 0; i < staging.size(); i++)
            chunks.saveAndFlush(new UploadChunk(session, i, 1, "unused", staging.get(i)));

        String unrelatedKey = prefix + "unrelated";
        File unrelated = new File(owner, outside, "unrelated.txt", "text/plain", 1, "unused");
        unrelated.setObjectKey(unrelatedKey);
        files.saveAndFlush(unrelated);

        for (int i = 0; i < fileKeys.size(); i++) upload(fileKeys.get(i), new byte[]{(byte) (i + 1)});
        for (int i = 0; i < staging.size(); i++) upload(staging.get(i), new byte[]{(byte) (10 + i)});
        upload(unrelatedKey, new byte[]{42});
        return new TreeFixture(owner.getId(), root.getId(), file.getId(), session.getId(),
                unrelated.getId(), current, history, staging, unrelatedKey);
    }

    private void upload(String key, byte[] bytes) {
        storage.upload(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    private void assertIsolatedResources() {
        String project = System.getProperty("minidrive.live-project", "");
        assertTrue(project.matches("md-it-[0-9a-f]{12}"), "live project marker must identify the disposable compose project");
        assertEquals("minidrive_it", jdbc.queryForObject("select current_database()", String.class));
        assertEquals("md_it", jdbc.queryForObject("select current_user", String.class));
    }

    private void assertExists(String key) {
        assertDoesNotThrow(() -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()), key);
    }
    private void assertMissing(String key) {
        assertTrue(isMissing(key), key);
    }
    private boolean isMissing(String key) {
        try {
            admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return false;
        } catch (ErrorResponseException response) {
            String code = response.errorResponse() == null ? "" : response.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code)) return true;
            throw new AssertionError("Silo object inspection failed with " + code, response);
        } catch (Exception failure) {
            throw new AssertionError("Silo object inspection did not confirm absence", failure);
        }
    }

    private record TreeFixture(UUID ownerId, UUID rootId, UUID fileId, UUID sessionId, UUID unrelatedFileId,
            String currentKey, List<String> historyKeys, List<String> stagingKeys, String unrelatedKey) {
        List<String> fileKeys() { List<String> keys = new ArrayList<>(); keys.add(currentKey); keys.addAll(historyKeys); return keys; }
        List<String> allKeys() { List<String> keys = fileKeys(); keys.addAll(stagingKeys); return keys; }
    }
}
