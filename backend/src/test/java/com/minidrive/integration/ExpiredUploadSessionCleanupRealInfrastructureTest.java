package com.minidrive.integration;

import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.entity.User;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.ExpiredUploadSessionCleanup;
import com.minidrive.service.HistoricalObjectOrphanReport;
import com.minidrive.service.UploadSessionService;
import com.minidrive.storage.StorageService;
import com.minidrive.storage.StorageNodeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Opt-in PostgreSQL + real Silo lifecycle/concurrency checks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.web-application-type=none",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "minidrive.storage.replication.enabled=false",
        "minidrive.upload-cleanup.interval-ms=3600000",
        "minidrive.upload-cleanup.initial-delay-ms=3600000",
        "minidrive.upload-cleanup.batch-size=2",
        "minidrive.upload-cleanup.claim-lease-ms=300000"
})
@Import(ExpiredUploadSessionCleanupRealInfrastructureTest.LiveStorageProperties.class)
@EnabledIfSystemProperty(named = "minidrive.live-upload-cleanup", matches = "true")
class ExpiredUploadSessionCleanupRealInfrastructureTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "md007-cleanup-" + RUN.substring(0, 16);
    private static final String ENDPOINT = System.getProperty("minidrive.live-upload-cleanup.silo-endpoint");
    private static final String ACCESS = System.getProperty("minidrive.live-upload-cleanup.silo-user", "md007");
    private static final String SECRET = System.getProperty("minidrive.live-upload-cleanup.silo-password");

    @Autowired private ExpiredUploadSessionCleanup cleanup;
    @Autowired private HistoricalObjectOrphanReport reconciliation;
    @Autowired private UploadSessionRepository sessions;
    @Autowired private UploadChunkRepository chunks;
    @Autowired private UserRepository users;
    @Autowired private FileRepository files;
    @Autowired private UploadSessionService uploads;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry metrics;
    @MockitoSpyBean private StorageService storage;

    private static MinioClient admin;

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("minidrive.live-upload-cleanup.jdbc-url"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getProperty("minidrive.live-upload-cleanup.db-user", "md007"));
        registry.add("spring.datasource.password", () -> required("minidrive.live-upload-cleanup.db-password"));
        registry.add("minio.endpoint", () -> required("minidrive.live-upload-cleanup.silo-endpoint"));
        registry.add("minio.access-key", () -> System.getProperty("minidrive.live-upload-cleanup.silo-user", "md007"));
        registry.add("minio.secret-key", () -> required("minidrive.live-upload-cleanup.silo-password"));
        registry.add("minio.bucket", () -> BUCKET);
    }

    @BeforeAll static void createDisposableBucket() throws Exception {
        admin = MinioClient.builder().endpoint(required("minidrive.live-upload-cleanup.silo-endpoint"))
                .credentials(System.getProperty("minidrive.live-upload-cleanup.silo-user", "md007"),
                        required("minidrive.live-upload-cleanup.silo-password")).build();
        assertFalse(admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @AfterAll static void removeDisposableBucket() throws Exception {
        if (admin == null) return;
        for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.get().objectName()).build());
        }
        admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
    }

    @BeforeEach void drainPriorExpiredTestFixtures() {
        while (cleanup.cleanupBatch() > 0) { /* finish only prior test-owned expired fixtures */ }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveStorageProperties {
        @Bean
        @Primary
        StorageNodeProperties isolatedSiloNode() {
            StorageNodeProperties properties = new StorageNodeProperties();
            StorageNodeProperties.Node node = new StorageNodeProperties.Node();
            node.setId("primary");
            node.setEnabled(true);
            node.setEndpoint(required("minidrive.live-upload-cleanup.silo-endpoint"));
            node.setAccessKey(System.getProperty("minidrive.live-upload-cleanup.silo-user", "md007"));
            node.setSecretKey(required("minidrive.live-upload-cleanup.silo-password"));
            node.setBucket(BUCKET);
            properties.setNodes(List.of(node));
            return properties;
        }
    }

    @Test void expiredMultipleChunksAreRemovedFromSiloAndPostgresButUnrelatedObjectSurvives() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1),
                List.of(new byte[]{1, 2, 3, 4, 5}, new byte[]{6, 7, 8, 9, 10}, new byte[]{11}));
        String unrelated = "_md007/" + RUN + "/unrelated";
        put(unrelated, new byte[]{42});
        assertEquals(1, cleanup.cleanupBatch());
        assertFalse(sessions.existsById(f.session().getId()));
        assertTrue(chunks.findBySessionIdOrderByChunkIndexAsc(f.session().getId()).isEmpty());
        for (String key : f.keys()) assertMissing(key);
        assertExists(unrelated);
    }

    @Test void nonExpiredSessionAndRealStagedObjectRemain() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(1), List.of(new byte[]{1, 2}));
        cleanup.cleanupBatch();
        assertTrue(sessions.existsById(f.session().getId()));
        assertExists(f.keys().get(0));
    }

    @Test void completedUploadFinalObjectSurvivesExpirySweep() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(1), List.of(new byte[]{1, 2, 3}));
        Authentication auth = auth(f.owner());
        var response = uploads.complete(auth, f.session().getId());
        String finalKey = files.findById(response.getId()).orElseThrow().getObjectKey();
        jdbc.update("update upload_sessions set expires_at=? where id=?", LocalDateTime.now().minusDays(1), f.session().getId());
        cleanup.cleanupBatch();
        assertTrue(sessions.existsById(f.session().getId()));
        assertArrayEquals(new byte[]{1, 2, 3}, read(finalKey));
    }

    @Test void reconciliationUsesRealPostgresReferencesAndSiloStatWithoutMutatingEitherStore() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().plusHours(1), List.of(new byte[]{4, 5, 6}));
        var response = uploads.complete(auth(f.owner()), f.session().getId());
        String key = files.findById(response.getId()).orElseThrow().getObjectKey();
        assertEquals(java.util.Optional.of(true), storage.exists(key));

        var present = reconciliation.reconcileBatch();
        assertEquals(0, present.count(HistoricalObjectOrphanReport.Status.MISSING_OBJECT));
        assertEquals(1, jdbc.queryForObject("select count(*) from object_locations where object_key=?", Integer.class, key));

        // Remove bytes directly through the test admin client, preserving the registered route and DB references.
        admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        org.mockito.Mockito.clearInvocations(storage);
        var absent = reconciliation.reconcileBatch();
        assertEquals(1, absent.findings().stream().filter(finding ->
                finding.status() == HistoricalObjectOrphanReport.Status.MISSING_OBJECT
                        && key.equals(finding.reference())).count());
        assertEquals(1, jdbc.queryForObject("select count(*) from object_locations where object_key=?", Integer.class, key));
        assertTrue(files.existsById(response.getId()), "reconciliation must preserve the current file reference");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(anyString());
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).upload(anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyString());
        assertThrows(Exception.class, () -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()));
    }

    @Test void missingStagingObjectDoesNotPreventConvergence() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1),
                List.of(new byte[]{1}, new byte[]{2}));
        storage.delete(f.keys().get(0));
        assertEquals(1, cleanup.cleanupBatch());
        assertFalse(sessions.existsById(f.session().getId()));
        assertMissing(f.keys().get(f.keys().size() - 1));
    }

    @Test void partialStorageFailureRetainsManifestThenRealRetryCompletesAfterLeaseRecovery() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1),
                List.of(new byte[]{1}, new byte[]{2}));
        AtomicBoolean injected = new AtomicBoolean();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.equals(f.keys().get(f.keys().size() - 1)) && injected.compareAndSet(false, true))
                throw new IllegalStateException("injected Silo adapter outage for test-owned key");
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());

        assertEquals(0, cleanup.cleanupBatch());
        assertEquals(UploadSessionStatus.CLEANING, sessions.findById(f.session().getId()).orElseThrow().getStatus());
        assertEquals(2, chunks.findBySessionIdOrderByChunkIndexAsc(f.session().getId()).size());
        assertMissing(f.keys().get(0));
        assertExists(f.keys().get(f.keys().size() - 1));

        jdbc.update("update upload_sessions set updated_at=? where id=?",
                LocalDateTime.now().minusMinutes(10), f.session().getId());
        doAnswer(invocation -> invocation.callRealMethod()).when(storage).delete(anyString());
        ExpiredUploadSessionCleanup restartedWorker = new ExpiredUploadSessionCleanup(
                sessions, chunks, storage, transactionManager, metrics, 2, 300000);
        assertEquals(1, restartedWorker.cleanupBatch());
        assertFalse(sessions.existsById(f.session().getId()));
        assertMissing(f.keys().get(f.keys().size() - 1));
    }

    @Test void postgresFinalizeFailureAfterRealDeletesRetainsManifestForIdempotentRetry() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1),
                List.of(new byte[]{1}, new byte[]{2}));
        String function = "md007_fail_delete_" + RUN.substring(0, 8);
        String trigger = function + "_trigger";
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin " +
                "if old.id = '" + f.session().getId() + "'::uuid then raise exception 'test-only finalize failure'; end if; " +
                "return old; end $$");
        jdbc.execute("create trigger " + trigger + " before delete on upload_sessions for each row execute function " + function + "()");
        try {
            assertEquals(0, cleanup.cleanupBatch());
            assertEquals(UploadSessionStatus.CLEANING, sessions.findById(f.session().getId()).orElseThrow().getStatus());
            assertEquals(2, chunks.findBySessionIdOrderByChunkIndexAsc(f.session().getId()).size());
            for (String key : f.keys()) assertMissing(key); // Silo deletion committed, PostgreSQL finalization rolled back.
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on upload_sessions");
            jdbc.execute("drop function if exists " + function + "()");
        }
        jdbc.update("update upload_sessions set updated_at=? where id=?",
                LocalDateTime.now().minusMinutes(10), f.session().getId());
        assertEquals(1, cleanup.cleanupBatch());
        assertFalse(sessions.existsById(f.session().getId()));
        assertTrue(chunks.findBySessionIdOrderByChunkIndexAsc(f.session().getId()).isEmpty());
    }

    @Test void independentWorkersAndIngestionRespectDurableCleanupClaim() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1), List.of(new byte[]{1}));
        CountDownLatch deleteEntered = new CountDownLatch(1);
        CountDownLatch allowDelete = new CountDownLatch(1);
        AtomicBoolean firstDelete = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstDelete.compareAndSet(true, false)) {
                deleteEntered.countDown();
                assertTrue(allowDelete.await(20, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());

        ExpiredUploadSessionCleanup secondWorker = new ExpiredUploadSessionCleanup(
                sessions, chunks, storage, transactionManager, metrics, 2, 300000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(cleanup::cleanupBatch);
            assertTrue(deleteEntered.await(10, TimeUnit.SECONDS));
            assertEquals(UploadSessionStatus.CLEANING, sessions.findById(f.session().getId()).orElseThrow().getStatus(),
                    "the claim must be committed before the remote delete starts");
            assertEquals(0, secondWorker.cleanupBatch());
            assertThrows(IllegalArgumentException.class, () -> uploads.ingestChunk(
                    auth(f.owner()), f.session().getId(), 0, new ByteArrayInputStream(new byte[]{9})));
            allowDelete.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS));
            assertFalse(sessions.existsById(f.session().getId()));
        } finally {
            allowDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test void staleFiveMinuteLeaseAllowsDuplicateDeleteButCannotTouchUnrelatedContent() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().minusMinutes(1),
                List.of(new byte[]{1}, new byte[]{2}));
        String unrelated = "_md007/" + RUN + "/lease-unrelated";
        put(unrelated, new byte[]{99});
        CountDownLatch firstDeleteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDelete = new CountDownLatch(1);
        CountDownLatch secondDeleteEntered = new CountDownLatch(1);
        AtomicInteger deletes = new AtomicInteger();
        doAnswer(invocation -> {
            if (deletes.incrementAndGet() == 1) {
                firstDeleteEntered.countDown();
                assertTrue(releaseFirstDelete.await(20, TimeUnit.SECONDS));
            } else {
                secondDeleteEntered.countDown();
            }
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());

        ExpiredUploadSessionCleanup delayedSecondWorker = new ExpiredUploadSessionCleanup(
                sessions, chunks, storage, transactionManager, metrics, 2, 300000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> slowWorker = pool.submit(cleanup::cleanupBatch);
            assertTrue(firstDeleteEntered.await(10, TimeUnit.SECONDS));
            // Advance only this disposable test row beyond the configured five-minute lease;
            // this models a storage call that remains active past the lease without a five-minute sleep.
            jdbc.update("update upload_sessions set updated_at=? where id=?",
                    LocalDateTime.now().minusMinutes(10), f.session().getId());
            Future<Integer> retryWorker = pool.submit(delayedSecondWorker::cleanupBatch);
            assertTrue(secondDeleteEntered.await(10, TimeUnit.SECONDS));
            releaseFirstDelete.countDown();
            assertEquals(1, slowWorker.get(20, TimeUnit.SECONDS));
            assertEquals(1, retryWorker.get(20, TimeUnit.SECONDS));
            assertFalse(sessions.existsById(f.session().getId()));
            for (String key : f.keys()) assertMissing(key);
            assertArrayEquals(new byte[]{99}, read(unrelated));
        } finally {
            releaseFirstDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test void completionLockWinsAgainstExpiryClaimAcrossMultipleChunks() throws Exception {
        Fixture f = fixture(UploadSessionStatus.INITIATED, LocalDateTime.now().plusSeconds(3),
                List.of(new byte[]{1, 2, 3, 4, 5}, new byte[]{6, 7, 8}));
        CountDownLatch assemblyReadStarted = new CountDownLatch(1);
        CountDownLatch finishAssembly = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstRead.compareAndSet(true, false)) {
                assemblyReadStarted.countDown();
                assertTrue(finishAssembly.await(20, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(storage).download(anyString());

        ExpiredUploadSessionCleanup competingWorker = new ExpiredUploadSessionCleanup(
                sessions, chunks, storage, transactionManager, metrics, 2, 300000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> completion = pool.submit(() -> uploads.complete(auth(f.owner()), f.session().getId()));
            assertTrue(assemblyReadStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(3500); // allow the persisted expiry time to pass while completion owns the row lock
            Future<Integer> sweep = pool.submit(competingWorker::cleanupBatch);
            Thread.sleep(300);
            assertFalse(sweep.isDone(), "cleanup should wait on the completion row lock");
            finishAssembly.countDown();
            completion.get(20, TimeUnit.SECONDS);
            assertEquals(0, sweep.get(20, TimeUnit.SECONDS));
            var fileId = sessions.findById(f.session().getId()).orElseThrow().getCompletedFileId();
            var file = files.findById(fileId).orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, read(file.getObjectKey()));
            assertEquals(UploadSessionStatus.COMPLETED, sessions.findById(f.session().getId()).orElseThrow().getStatus());
        } finally {
            finishAssembly.countDown();
            pool.shutdownNow();
        }
    }

    @Test void manyExpiredRowsAreStillProcessedInConfiguredBoundedBatch() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 3; i++) fixtures.add(fixture(UploadSessionStatus.INITIATED,
                LocalDateTime.now().minusHours(1), List.of()));
        assertEquals(2, cleanup.cleanupBatch());
        assertEquals(1, fixtures.stream().filter(f -> sessions.existsById(f.session().getId())).count());
    }

    private Fixture fixture(UploadSessionStatus status, LocalDateTime expires, List<byte[]> data) {
        User owner = users.saveAndFlush(new User("MD007", "md007-" + UUID.randomUUID() + "@test.invalid", "hash"));
        long size = data.stream().mapToLong(x -> x.length).sum();
        UploadSession session = sessions.saveAndFlush(new UploadSession(owner, null, null,
                "multi.bin", "application/octet-stream", size, 5, Math.max(1, data.size()), status, expires));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            String key = "_uploads/" + owner.getId() + "/" + session.getId() + "/" + i;
            put(key, data.get(i));
            chunks.saveAndFlush(new UploadChunk(session, i, data.get(i).length, checksum(data.get(i)), key));
            keys.add(key);
        }
        return new Fixture(owner, session, keys);
    }

    private void put(String key, byte[] bytes) {
        storage.upload(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    private byte[] read(String key) throws Exception {
        try (var stream = storage.download(key)) { return stream.readAllBytes(); }
    }

    private void assertExists(String key) throws Exception {
        admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
    }

    private void assertMissing(String key) {
        assertThrows(Exception.class, () -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()));
    }

    private Authentication auth(User user) {
        return new UsernamePasswordAuthenticationToken(user.getEmail(), "");
    }

    private String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing system property " + property);
        return value;
    }

    private record Fixture(User owner, UploadSession session, List<String> keys) { }
}
