package com.minidrive.integration;

import com.minidrive.storage.NodeStorageClient;
import com.minidrive.storage.RoutingStorageService;
import com.minidrive.storage.StableStorageNodeSelector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in PostgreSQL + two real Silo nodes test. The secondary outage is simulated by a dead endpoint. */
@EnabledIfSystemProperty(named = "minidrive.live-pg-minio", matches = "true")
class RoutingStorageDeletionRealInfrastructureTest {
    @Test
    void failedDeleteSurvivesRestartRetryAndNeverEntersRepair() throws Exception {
        String dbUrl = System.getProperty("minidrive.live-pg-minio.db-url",
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:55432/minidrive"));
        String dbUser = System.getProperty("minidrive.live-pg-minio.db-user",
                System.getenv().getOrDefault("DB_USERNAME", "minidrive"));
        String dbPassword = System.getProperty("minidrive.live-pg-minio.db-password",
                System.getenv().getOrDefault("DB_PASSWORD", "minidrive"));
        String primaryEndpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000");
        String primaryBucket = System.getenv().getOrDefault("MINIO_BUCKET", "minidrive-files");
        String secondaryEndpoint = System.getenv().getOrDefault("MINIO_SECONDARY_ENDPOINT", "http://127.0.0.1:9100");
        String secondaryBucket = System.getenv().getOrDefault("MINIO_SECONDARY_BUCKET", "minidrive-files-secondary");
        String access = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        String secret = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        String key = "_md002-verify/" + UUID.randomUUID();
        byte[] bytes = "md002-real-silo-content".getBytes(StandardCharsets.UTF_8);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(dbUrl, dbUser, dbPassword);
        Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        NodeStorageClient primary = client("primary", primaryEndpoint, access, secret, primaryBucket);
        NodeStorageClient secondary = client("secondary", secondaryEndpoint, access, secret, secondaryBucket);
        NodeStorageClient unavailableSecondary = client("secondary", "http://127.0.0.1:1", access, secret, secondaryBucket);
        assertTrue(primary.isHealthy(), "primary Silo must be reachable");
        assertTrue(secondary.isHealthy(), "secondary Silo must be reachable");
        RoutingStorageService failing = service(jdbc, primary, unavailableSecondary);
        RoutingStorageService recovered = service(jdbc, primary, secondary);
        failing.setTransactionManager(transactionManager);

        try {
            primary.upload(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            secondary.upload(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?, 'primary')", key);
            jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,updated_at) "
                    + "values (?, 'primary','HEALTHY',?,? ,current_timestamp), (?, 'secondary','HEALTHY',?,? ,current_timestamp)",
                    key, bytes.length, "md002-checksum", key, bytes.length, "md002-checksum");

            // FileService invokes storage deletion inside a transaction. Force
            // that caller transaction to roll back and confirm the tombstone
            // committed independently of it.
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> failing.delete(key)));
            assertEquals(2, jdbc.queryForObject("select count(*) from object_replicas where object_key=? and status='DELETING'", Integer.class, key));
            assertArrayEquals(bytes, secondary.download(key).readAllBytes(), "the only reachable copy remains available");
            assertEquals(0, failing.repairOnce(), "a deletion tombstone must never be treated as repair work");
            assertThrows(RuntimeException.class, () -> primary.download(key), "primary was deleted before the secondary outage");

            // A new service instance models application restart: the persisted
            // replica rows retain the retry target and deletion intent.
            recovered.delete(key);
            assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key=?", Integer.class, key));
            assertEquals(0, jdbc.queryForObject("select count(*) from object_replicas where object_key=?", Integer.class, key));
            assertEquals(0, recovered.repairOnce());
            assertThrows(RuntimeException.class, () -> primary.download(key));
            assertThrows(RuntimeException.class, () -> secondary.download(key));
        } finally {
            jdbc.update("delete from object_locations where object_key=?", key);
            jdbc.update("delete from object_replicas where object_key=?", key);
            try { primary.delete(key); } catch (RuntimeException ignored) { }
            try { secondary.delete(key); } catch (RuntimeException ignored) { }
        }
    }

    private static NodeStorageClient client(String id, String endpoint, String access, String secret, String bucket) {
        return new NodeStorageClient(id, MinioClient.builder().endpoint(endpoint).credentials(access, secret).build(), bucket);
    }

    private static RoutingStorageService service(JdbcTemplate jdbc, NodeStorageClient primary, NodeStorageClient secondary) {
        return new RoutingStorageService(jdbc, Map.of("primary", primary, "secondary", secondary),
                new StableStorageNodeSelector(), new SimpleMeterRegistry(), true, "primary");
    }
}
