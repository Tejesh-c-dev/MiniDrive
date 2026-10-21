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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutingStorageReplicationTest {
    private JdbcTemplate jdbc;
    private NodeStorageClient primary;
    private NodeStorageClient secondary;
    private RoutingStorageService storage;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:replication" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table object_locations(object_key varchar(512) primary key, storage_node_id varchar(64) not null)");
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

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes());
    }
}
