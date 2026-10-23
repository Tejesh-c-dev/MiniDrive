package com.minidrive.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReplicaIntegrityBackfillTest {
    private JdbcTemplate jdbc;
    private NodeStorageClient primary;
    private NodeStorageClient secondary;
    private ReplicaIntegrityBackfill backfill;
    private byte[] content;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:backfill" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table file_versions(object_key varchar(512), size_bytes bigint)");
        jdbc.execute("create table files(object_key varchar(512), size_bytes bigint)");
        jdbc.execute("create table object_locations(object_key varchar(512) primary key, storage_node_id varchar(64) not null)");
        jdbc.execute("create table upload_chunks(staging_object_key varchar(512))");
        jdbc.execute("create table object_replicas(object_key varchar(512), storage_node_id varchar(64), status varchar(20), "
                + "size_bytes bigint, checksum varchar(64), attempt_count int, last_error varchar(1000), "
                + "next_attempt_at timestamp, verified_at timestamp, updated_at timestamp, primary key(object_key,storage_node_id))");
        primary = mock(NodeStorageClient.class);
        secondary = mock(NodeStorageClient.class);
        content = "legacy bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(primary.download(anyString())).thenAnswer(i -> bytes(content));
        when(secondary.download(anyString())).thenAnswer(i -> bytes(content));
        Map<String, NodeStorageClient> nodes = Map.of("primary", primary, "secondary", secondary);
        backfill = new ReplicaIntegrityBackfill(jdbc, nodes, new DataSourceTransactionManager(ds));
        jdbc.update("insert into file_versions values ('legacy-key',?)", content.length);
        jdbc.update("insert into files values ('legacy-key',?)", content.length);
        jdbc.update("insert into object_locations values ('legacy-key','primary')");
    }

    @Test
    void streamsLegacyBytesAndInitializesVerifiedReplicaStates() throws Exception {
        var report = backfill.run();
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        assertEquals(1, report.verified());
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where status='HEALTHY' and verified_at is not null", Integer.class));
        assertEquals(expected, jdbc.queryForObject("select checksum from object_replicas where storage_node_id='primary'", String.class));
    }

    @Test
    void corruptSecondaryAndMissingPrimaryAreNeverHealthy() {
        when(secondary.download(anyString())).thenAnswer(i -> bytes("corrupt".getBytes()));
        when(primary.download("missing")).thenThrow(new RuntimeException("object missing"));
        jdbc.update("insert into file_versions values ('missing',1)");
        jdbc.update("insert into files values ('missing',1)");
        jdbc.update("insert into object_locations values ('missing','primary')");

        var report = backfill.run();

        assertEquals(2, report.scanned());
        assertEquals(1, report.verified());
        assertEquals(1, report.failed());
        assertEquals(1, report.secondaryUnverified());
        assertEquals(1, jdbc.queryForObject("select count(*) from object_replicas where object_key='legacy-key' and storage_node_id='secondary' and status='FAILED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='missing' and status='HEALTHY'", Integer.class));
    }

    @Test
    void restartPreservesVerifiedEntriesAndDeletionTombstones() {
        backfill.run();
        String checksum = jdbc.queryForObject("select checksum from object_replicas where object_key='legacy-key' and storage_node_id='primary'", String.class);
        jdbc.update("update object_replicas set status='DELETING' where object_key='legacy-key' and storage_node_id='secondary'");

        var second = backfill.run();

        assertEquals(0, second.verified());
        assertEquals(checksum, jdbc.queryForObject("select checksum from object_replicas where object_key='legacy-key' and storage_node_id='primary'", String.class));
        assertEquals("DELETING", jdbc.queryForObject("select status from object_replicas where object_key='legacy-key' and storage_node_id='secondary'", String.class));
    }

    @Test
    void replicationEnablementIsBlockedUntilBackfillAndThenLegacyReadWorks() throws Exception {
        assertThrows(IllegalStateException.class, () -> new RoutingStorageService(jdbc,
                Map.of("primary", primary, "secondary", secondary), (key, ids) -> "primary",
                new SimpleMeterRegistry(), true, "primary"));

        assertEquals(1, backfill.run().verified());
        RoutingStorageService enabled = new RoutingStorageService(jdbc,
                Map.of("primary", primary, "secondary", secondary), (key, ids) -> "primary",
                new SimpleMeterRegistry(), true, "primary");

        assertArrayEquals(content, assertDoesNotThrow(() -> enabled.download("legacy-key")).readAllBytes());
    }

    @Test
    void concurrentRunsSerializeAndKeepOneIdempotentRowPerCopy() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> backfill.run());
            var second = pool.submit(() -> backfill.run());
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='legacy-key'", Integer.class));
    }

    @Test
    void failedMetadataInsertRollsBackAndRerunRecovers() {
        RejectFirstReplicaInsert.reject.set(true);
        jdbc.execute("create trigger fail_replica_insert before insert on object_replicas for each row "
                + "call 'com.minidrive.storage.ReplicaIntegrityBackfillTest$RejectFirstReplicaInsert'");

        var interrupted = backfill.run();

        assertEquals(1, interrupted.failed());
        assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key='legacy-key'", Integer.class));
        assertEquals(1, backfill.run().verified());
        assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key='legacy-key'", Integer.class));
    }

    public static class RejectFirstReplicaInsert implements org.h2.api.Trigger {
        static final java.util.concurrent.atomic.AtomicBoolean reject = new java.util.concurrent.atomic.AtomicBoolean();
        @Override public void init(java.sql.Connection connection, String schemaName, String triggerName,
                                   String tableName, boolean before, int type) { }
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                throws java.sql.SQLException {
            if (reject.compareAndSet(true, false)) throw new java.sql.SQLException("simulated metadata persistence failure");
        }
        @Override public void close() { }
        @Override public void remove() { }
    }

    private static InputStream bytes(byte[] bytes) { return new ByteArrayInputStream(bytes); }
}
