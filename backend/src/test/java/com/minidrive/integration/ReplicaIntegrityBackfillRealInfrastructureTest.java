package com.minidrive.integration;

import com.minidrive.storage.NodeStorageClient;
import com.minidrive.storage.ReplicaIntegrityBackfill;
import com.minidrive.storage.RoutingStorageService;
import com.minidrive.storage.StableStorageNodeSelector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in legacy migration against PostgreSQL and two real Silo-compatible nodes. */
@EnabledIfSystemProperty(named = "minidrive.live-pg-minio", matches = "true")
class ReplicaIntegrityBackfillRealInfrastructureTest {
    @Test
    void legacySingleNodeObjectBackfillsThenDownloadsInReplicationMode() throws Exception {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/minidrive_it", System.getenv("DB_URL"));
        assertEquals("md_it", System.getenv("DB_USERNAME"));
        assertNotEquals("minidrive", System.getenv("DB_PASSWORD"));
        assertEquals("http://127.0.0.1:9200", System.getenv("MINIO_ENDPOINT"));
        assertEquals("http://127.0.0.1:9300", System.getenv("MINIO_SECONDARY_ENDPOINT"));
        assertEquals("md_it_silo", System.getenv("MINIO_ACCESS_KEY"));
        assertNotEquals("minioadmin", System.getenv("MINIO_SECRET_KEY"));
        assertEquals("minidrive-it-primary", System.getenv("MINIO_BUCKET"));
        assertEquals("minidrive-it-secondary", System.getenv("MINIO_SECONDARY_BUCKET"));
        String dbUrl = System.getProperty("minidrive.live-pg-minio.db-url",
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:55432/minidrive"));
        String dbUser = System.getProperty("minidrive.live-pg-minio.db-user",
                System.getenv().getOrDefault("DB_USERNAME", "minidrive"));
        String dbPassword = System.getProperty("minidrive.live-pg-minio.db-password",
                System.getenv().getOrDefault("DB_PASSWORD", "minidrive"));
        String primaryEndpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000");
        String secondaryEndpoint = System.getenv().getOrDefault("MINIO_SECONDARY_ENDPOINT", "http://127.0.0.1:9100");
        String primaryBucket = System.getenv().getOrDefault("MINIO_BUCKET", "minidrive-files");
        String secondaryBucket = System.getenv().getOrDefault("MINIO_SECONDARY_BUCKET", "minidrive-files-secondary");
        String access = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        String secret = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        var ds = new DriverManagerDataSource(dbUrl, dbUser, dbPassword);
        Flyway.configure().dataSource(ds).baselineOnMigrate(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var tx = new DataSourceTransactionManager(ds);
        NodeStorageClient primary = client("primary", primaryEndpoint, access, secret, primaryBucket);
        NodeStorageClient secondary = client("secondary", secondaryEndpoint, access, secret, secondaryBucket);
        assertTrue(primary.isHealthy(), "primary Silo must be reachable");
        assertTrue(secondary.isHealthy(), "secondary Silo must be reachable");

        String suffix = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String key = "_md003-legacy/" + suffix;
        byte[] content = ("legacy object created before replica metadata " + suffix).getBytes(StandardCharsets.UTF_8);
        Map<String, NodeStorageClient> nodes = Map.of("primary", primary, "secondary", secondary);
        RoutingStorageService legacyStorage = new RoutingStorageService(jdbc, nodes,
                new StableStorageNodeSelector(), new SimpleMeterRegistry(), false, "primary");
        try {
            primary.upload(key, new ByteArrayInputStream(content), content.length, "text/plain");
            jdbc.update("insert into users(id,name,email,password_hash,created_at,updated_at) values (?,?,?,?,current_timestamp,current_timestamp)",
                    userId, "MD003 integration", "md003-" + suffix + "@example.test", "unused");
            jdbc.update("insert into files(id,owner_id,name,object_key,content_type,size_bytes,created_at,updated_at) "
                            + "values (?,?,?,?,'text/plain',?,current_timestamp,current_timestamp)",
                    fileId, userId, "legacy.txt", key, content.length);
            jdbc.update("insert into file_versions(id,file_id,version_number,object_key,original_filename,content_type,size_bytes,created_by,created_at,checksum) "
                            + "values (?,?,1,?,'legacy.txt','text/plain',?,?,current_timestamp,null)",
                    UUID.randomUUID(), fileId, key, content.length, userId);
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?, 'primary')", key);

            ReplicaIntegrityBackfill backfill = new ReplicaIntegrityBackfill(jdbc, nodes, tx);
            var report = backfill.run();
            assertEquals(1, report.verified());
            assertEquals(0, report.failed());
            RoutingStorageService replicationStorage = new RoutingStorageService(jdbc, nodes,
                    new StableStorageNodeSelector(), new SimpleMeterRegistry(), true, "primary");
            assertArrayEquals(content, replicationStorage.download(key).readAllBytes());
        } finally {
            try { legacyStorage.delete(key); } catch (RuntimeException ignored) { }
            jdbc.update("delete from files where id=?", fileId);
            jdbc.update("delete from users where id=?", userId);
            jdbc.update("delete from object_locations where object_key=?", key);
            jdbc.update("delete from object_replicas where object_key=?", key);
        }
    }

    private static NodeStorageClient client(String id, String endpoint, String access, String secret, String bucket) {
        return new NodeStorageClient(id, MinioClient.builder().endpoint(endpoint).credentials(access, secret).build(), bucket);
    }
}
