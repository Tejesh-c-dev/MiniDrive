package com.minidrive.integration;

import com.minidrive.storage.NodeStorageClient;
import com.minidrive.storage.ReplicaIntegrityBackfill;
import com.minidrive.storage.ReplicationSpoolManager;
import com.minidrive.storage.ReplicationSpoolProperties;
import com.minidrive.storage.RoutingStorageService;
import com.minidrive.storage.StableStorageNodeSelector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in bounded replication upload against the guarded disposable PostgreSQL and two Silo nodes. */
@EnabledIfSystemProperty(named = "minidrive.live-pg-minio", matches = "true")
class ReplicationSpoolRealInfrastructureTest {
    @Test void replicatedUploadVerifiesBothNodesAndRemovesLocalSpool(@TempDir Path temp) throws Exception {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/minidrive_it", System.getenv("DB_URL"));
        assertEquals("md_it", System.getenv("DB_USERNAME"));
        assertNotEquals("minidrive", System.getenv("DB_PASSWORD"));
        assertEquals("http://127.0.0.1:9200", System.getenv("MINIO_ENDPOINT"));
        assertEquals("http://127.0.0.1:9300", System.getenv("MINIO_SECONDARY_ENDPOINT"));
        assertEquals("minidrive-it-primary", System.getenv("MINIO_BUCKET"));
        assertEquals("minidrive-it-secondary", System.getenv("MINIO_SECONDARY_BUCKET"));
        var source = new DriverManagerDataSource(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        Flyway.configure().dataSource(source).baselineOnMigrate(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var tx = new DataSourceTransactionManager(source);
        String access = System.getenv("MINIO_ACCESS_KEY"), secret = System.getenv("MINIO_SECRET_KEY");
        NodeStorageClient primary = client("primary", System.getenv("MINIO_ENDPOINT"), access, secret, System.getenv("MINIO_BUCKET"));
        NodeStorageClient secondary = client("secondary", System.getenv("MINIO_SECONDARY_ENDPOINT"),
                System.getenv("MINIO_SECONDARY_ACCESS_KEY"), System.getenv("MINIO_SECONDARY_SECRET_KEY"), System.getenv("MINIO_SECONDARY_BUCKET"));
        assertTrue(primary.isHealthy()); assertTrue(secondary.isHealthy());
        Map<String, NodeStorageClient> nodes = Map.of("primary", primary, "secondary", secondary);
        new ReplicaIntegrityBackfill(jdbc, nodes, tx).run();
        String key = "_md015-spool/" + UUID.randomUUID();
        byte[] content = new byte[128 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var policy = new ReplicationSpoolProperties(); policy.setDirectory(temp.resolve("replication-spool")); policy.setMinFreeBytes(0);
        var metrics = new SimpleMeterRegistry();
        try (var spool = new ReplicationSpoolManager(policy, metrics, true)) {
            RoutingStorageService routed = new RoutingStorageService(jdbc, nodes, new StableStorageNodeSelector(),
                    metrics, true, "primary", spool);
            routed.setTransactionManager(tx);
            try {
                routed.upload(key, new ByteArrayInputStream(content), content.length, "application/octet-stream");
                try (var primaryDownload = primary.download(key); var secondaryDownload = secondary.download(key)) {
                    assertArrayEquals(content, primaryDownload.readAllBytes());
                    assertArrayEquals(content, secondaryDownload.readAllBytes());
                }
                assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key=? and status='HEALTHY' and checksum=? and size_bytes=?",
                        Integer.class, key, checksum, content.length));
                try (var files = Files.list(policy.getDirectory())) {
                    assertEquals(0, files.filter(p -> p.toString().endsWith(".spool")).count());
                }
            } finally {
                try { routed.delete(key); } catch (RuntimeException ignored) { }
                jdbc.update("delete from object_locations where object_key=?", key);
                jdbc.update("delete from object_replicas where object_key=?", key);
                try { primary.delete(key); } catch (RuntimeException ignored) { }
                try { secondary.delete(key); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static NodeStorageClient client(String id, String endpoint, String access, String secret, String bucket) {
        return new NodeStorageClient(id, MinioClient.builder().endpoint(endpoint).credentials(access, secret).build(), bucket);
    }
}
