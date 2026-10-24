package com.minidrive.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutingStorageReplicationTest {
    private JdbcTemplate jdbc;
    private NodeStorageClient primary;
    private NodeStorageClient secondary;
    private RoutingStorageService storage;

    @org.junit.jupiter.api.io.TempDir Path spoolDir;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:replication" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table file_versions(object_key varchar(512), size_bytes bigint)");
        jdbc.execute("create table files(object_key varchar(512), size_bytes bigint)");
        jdbc.execute("create table object_locations(object_key varchar(512) primary key, storage_node_id varchar(64) not null)");
        jdbc.execute("create table upload_chunks(staging_object_key varchar(512))");
        jdbc.execute("create table object_replicas(object_key varchar(512), storage_node_id varchar(64), status varchar(20), "
                + "size_bytes bigint, checksum varchar(64), attempt_count int, last_error varchar(1000), "
                + "next_attempt_at timestamp, verified_at timestamp, updated_at timestamp, "
                + "primary key(object_key, storage_node_id))");
        primary = mock(NodeStorageClient.class);
        secondary = mock(NodeStorageClient.class);
        when(primary.download(anyString())).thenAnswer(invocation -> bytes("replicated-content"));
        when(secondary.download(anyString())).thenAnswer(invocation -> bytes("replicated-content"));
        storage = new RoutingStorageService(jdbc, Map.of("primary", primary, "secondary", secondary),
                (key, ids) -> "primary", new SimpleMeterRegistry(), true, "primary");
    }

    @Test
    void uploadTracksVerifiedCopiesAndFallbackReadsSecondary() throws Exception {
        byte[] content = "replicated-content".getBytes();
        storage.upload("object", new ByteArrayInputStream(content), content.length, "text/plain");

        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='object' and status='HEALTHY'", Integer.class));
        verify(primary).upload(eq("object"), any(InputStream.class), eq((long) content.length), eq("text/plain"));
        verify(secondary).upload(eq("object"), any(InputStream.class), eq((long) content.length), eq("text/plain"));

        doThrow(new RuntimeException("primary offline")).when(primary).download("object");
        assertArrayEquals(content, storage.download("object").readAllBytes());
    }

    @Test
    void failedSecondaryIsPendingAndRepairIsIdempotent() {
        byte[] content = "replicated-content".getBytes();
        doThrow(new RuntimeException("secondary offline")).when(secondary)
                .upload(eq("object"), any(), eq((long) content.length), anyString());
        storage.upload("object", new ByteArrayInputStream(content), content.length, "text/plain");

        assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where storage_node_id='secondary' and status='FAILED'", Integer.class));
        reset(secondary);
        when(secondary.download(anyString())).thenAnswer(invocation -> bytes("replicated-content"));
        jdbc.update("update object_replicas set next_attempt_at=current_timestamp where storage_node_id='secondary'");
        assertEquals(1, storage.repairOnce());
        assertEquals(0, storage.repairOnce());
        assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where object_key='object' and storage_node_id='secondary'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where object_key='object' and storage_node_id='secondary' and status='HEALTHY'", Integer.class));
    }

    @Test
    void primaryFailureRemovesSpoolAndReleasesReservation() throws Exception {
        var policy = new ReplicationSpoolProperties(); policy.setDirectory(spoolDir.resolve("spool"));
        policy.setMinFreeBytes(0);
        var registry = new SimpleMeterRegistry();
        try (var spoolManager = new ReplicationSpoolManager(policy, registry, true)) {
            var routed = new RoutingStorageService(jdbc, Map.of("primary", primary, "secondary", secondary),
                    (key, ids) -> "primary", registry, true, "primary", spoolManager);
            doThrow(new RuntimeException("primary offline")).when(primary)
                    .upload(eq("failed"), any(), anyLong(), anyString());
            assertThrows(RuntimeException.class, () -> routed.upload("failed", new ByteArrayInputStream(new byte[]{1, 2}), 2, "text/plain"));
            try (var files = Files.list(policy.getDirectory())) {
                assertEquals(0, files.filter(path -> path.toString().endsWith(".spool")).count());
            }
            assertEquals(0, registry.get("minidrive_replication_spool_active").gauge().value());
            assertEquals(0, registry.get("minidrive_replication_spool_reserved_bytes").gauge().value());
            verify(secondary, never()).upload(eq("failed"), any(), anyLong(), anyString());
        }
    }

    @Test
    void secondaryFailureKeepsRepairStateAndRemovesSpool() throws Exception {
        var policy = new ReplicationSpoolProperties(); policy.setDirectory(spoolDir.resolve("secondary-spool"));
        policy.setMinFreeBytes(0);
        var registry = new SimpleMeterRegistry();
        try (var spoolManager = new ReplicationSpoolManager(policy, registry, true)) {
            var routed = new RoutingStorageService(jdbc, Map.of("primary", primary, "secondary", secondary),
                    (key, ids) -> "primary", registry, true, "primary", spoolManager);
            doThrow(new RuntimeException("secondary offline")).when(secondary)
                    .upload(eq("partial-replica"), any(), anyLong(), anyString());
            byte[] bytes = "replicated-content".getBytes();
            routed.upload("partial-replica", new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where object_key='partial-replica' and storage_node_id='secondary' and status='FAILED'", Integer.class));
            try (var files = Files.list(policy.getDirectory())) {
                assertEquals(0, files.filter(path -> path.toString().endsWith(".spool")).count());
            }
            assertEquals(0, registry.get("minidrive_replication_spool_reserved_bytes").gauge().value());
        }
    }

    @Test
    void checksumMismatchIsNotAcceptedAsHealthy() {
        when(secondary.download(anyString())).thenAnswer(invocation -> bytes("corrupt"));
        byte[] content = "replicated-content".getBytes();
        storage.upload("object", new ByteArrayInputStream(content), content.length, "text/plain");

        assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where storage_node_id='secondary' and status='FAILED'", Integer.class));
    }

    @Test
    void downloadTransactionAllowsReplicaVerificationMetadataUpdates() throws Exception {
        var method = com.minidrive.service.FileService.class.getMethod(
                "download",
                org.springframework.security.core.Authentication.class,
                java.util.UUID.class);

        assertFalse(method.getAnnotation(Transactional.class).readOnly());
    }

    @Test
    void deleteRemovesEveryPhysicalCopyAndReplicaMetadata() {
        jdbc.update("insert into object_locations(object_key,storage_node_id) values ('object','primary')");
        jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,updated_at) values "
                + "('object','primary','HEALTHY',1,'hash',current_timestamp),"
                + "('object','secondary','HEALTHY',1,'hash',current_timestamp)");

        storage.delete("object");

        verify(primary).delete("object");
        verify(secondary).delete("object");
        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key='object'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
    }

    @Test
    void failedReplicaDeleteKeepsRoutingReferencesForRetry() {
        jdbc.update("insert into object_locations(object_key,storage_node_id) values ('object','primary')");
        jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,updated_at) values "
                + "('object','primary','HEALTHY',1,'hash',current_timestamp),"
                + "('object','secondary','HEALTHY',1,'hash',current_timestamp)");
        doThrow(new RuntimeException("secondary offline")).when(secondary).delete("object");

        assertThrows(RuntimeException.class, () -> storage.delete("object"));
        assertEquals(1, jdbc.queryForObject("select count(*) from object_locations where object_key='object'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='object' and status='DELETING'", Integer.class));

        // The primary has already been deleted, while the secondary still has
        // the only usable copy. Repair must not recreate the primary.
        assertEquals(0, storage.repairOnce());
        verify(primary, never()).upload(eq("object"), any(InputStream.class), anyLong(), anyString());

        reset(secondary);
        storage.delete("object");
        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key='object'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
        assertEquals(0, storage.repairOnce());
    }

    @Test
    void failedPrimaryDeleteRetainsTheOnlyUsableCopyAndRetryTargetsBothNodes() {
        jdbc.update("insert into object_locations(object_key,storage_node_id) values ('object','primary')");
        jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,updated_at) values "
                + "('object','primary','HEALTHY',1,'hash',current_timestamp),"
                + "('object','secondary','HEALTHY',1,'hash',current_timestamp)");
        doThrow(new RuntimeException("primary offline")).when(primary).delete("object");

        assertThrows(RuntimeException.class, () -> storage.delete("object"));
        verify(secondary).delete("object");
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='object' and status='DELETING'", Integer.class));
        assertEquals(0, storage.repairOnce());

        reset(primary);
        storage.delete("object");
        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key='object'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
    }

    @Test
    void deletionRacingWithRepairWaitsThenRemovesTheRepairedCopy() throws Exception {
        jdbc.update("insert into object_locations(object_key,storage_node_id) values ('object','primary')");
        byte[] content = "replicated-content".getBytes();
        String checksum = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,updated_at) values "
                + "('object','primary','HEALTHY',?,?,current_timestamp),"
                + "('object','secondary','FAILED',?,?,current_timestamp)", content.length, checksum, content.length, checksum);
        jdbc.update("update object_replicas set next_attempt_at=current_timestamp where storage_node_id='secondary'");
        CountDownLatch replicaUploadStarted = new CountDownLatch(1);
        CountDownLatch allowReplicaUploadToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            replicaUploadStarted.countDown();
            assertTrue(allowReplicaUploadToFinish.await(5, TimeUnit.SECONDS));
            return null;
        }).when(secondary).upload(eq("object"), any(InputStream.class), anyLong(), anyString());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var repair = executor.submit(() -> storage.repairOnce());
            assertTrue(replicaUploadStarted.await(5, TimeUnit.SECONDS));
            var deletion = executor.submit(() -> storage.delete("object"));
            Thread.sleep(50);
            assertFalse(deletion.isDone(), "delete must wait for an already-running repair of the same key");
            allowReplicaUploadToFinish.countDown();
            assertEquals(1, repair.get(5, TimeUnit.SECONDS));
            deletion.get(5, TimeUnit.SECONDS);
        } finally {
            allowReplicaUploadToFinish.countDown();
            executor.shutdownNow();
        }

        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key='object'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='object'", Integer.class));
        var order = inOrder(secondary);
        order.verify(secondary).upload(eq("object"), any(InputStream.class), anyLong(), anyString());
        order.verify(secondary).delete("object");
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes());
    }
}
