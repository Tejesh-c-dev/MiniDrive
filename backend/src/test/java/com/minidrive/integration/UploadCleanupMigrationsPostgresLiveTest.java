package com.minidrive.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in migration checks against a disposable PostgreSQL server. */
@EnabledIfSystemProperty(named = "minidrive.live-upload-cleanup", matches = "true")
class UploadCleanupMigrationsPostgresLiveTest {
    private final String adminUrl = System.getProperty("minidrive.live-upload-cleanup.admin-url");
    private final String username = System.getProperty("minidrive.live-upload-cleanup.db-user", "md007");
    private final String password = System.getProperty("minidrive.live-upload-cleanup.db-password");
    private final String database = "md007_migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private JdbcTemplate jdbc;

    @BeforeEach void createDisposableDatabase() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/postgres", adminUrl,
                "Migration tests may create/drop databases only on the disposable integration PostgreSQL instance");
        assertEquals("md_it", username);
        assertNotEquals("minidrive", password);
        assertNotNull(adminUrl, "Supply the isolated PostgreSQL admin URL");
        DriverManagerDataSource admin = new DriverManagerDataSource(adminUrl, username, password);
        new JdbcTemplate(admin).execute("CREATE DATABASE " + database);
        DriverManagerDataSource target = targetDataSource();
        jdbc = new JdbcTemplate(target);
    }

    @AfterEach void dropOnlyDisposableDatabase() {
        if (adminUrl == null) return;
        DriverManagerDataSource admin = new DriverManagerDataSource(adminUrl, username, password);
        JdbcTemplate q = new JdbcTemplate(admin);
        q.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='" + database + "'");
        q.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test void freshDatabaseRunsCompleteMigrationChainAndCreatesExpectedCleanupIndex() {
        Flyway flyway = Flyway.configure().dataSource(targetDataSource()).load();
        assertEquals(19, flyway.migrate().migrationsExecuted);
        assertEquals("CLEANING", jdbc.queryForObject("select 'CLEANING'", String.class));
        assertTrue(jdbc.queryForObject("select count(*) from information_schema.columns " +
                "where table_name='upload_sessions' and column_name in ('status','expires_at','updated_at')", Integer.class) == 3);
        String constraint = jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint " +
                "where conname='ck_upload_sessions_status'", String.class);
        assertTrue(constraint.contains("CLEANING"));
        String index = jdbc.queryForObject("select indexdef from pg_indexes where indexname='idx_upload_sessions_cleanup'", String.class);
        assertTrue(index.contains("(status, updated_at, expires_at)"), index);

        UUID owner = insertOwner("fresh");
        for (String state : List.of("INITIATED", "COMPLETED", "ABORTED", "EXPIRED", "CLEANING")) {
            insertSession(owner, UUID.randomUUID(), state, LocalDateTime.now().minusHours(1));
        }
        assertEquals(5, jdbc.queryForObject("select count(*) from upload_sessions", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from upload_sessions where status in ('INITIATED','EXPIRED','CLEANING')", Integer.class));
    }

    @Test void v18UpgradePreservesExistingStatesAndCompletedSessionsRemainIneligible() {
        Flyway v18 = Flyway.configure().dataSource(targetDataSource()).target(MigrationVersion.fromVersion("18")).load();
        assertEquals(18, v18.migrate().migrationsExecuted);
        UUID owner = insertOwner("upgrade");
        UUID active = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID aborted = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
        insertSession(owner, active, "INITIATED", LocalDateTime.now().plusHours(1), created);
        insertSession(owner, completed, "COMPLETED", LocalDateTime.now().minusDays(3), created);
        insertSession(owner, expired, "EXPIRED", LocalDateTime.now().minusDays(3), created);
        insertSession(owner, aborted, "ABORTED", LocalDateTime.now().minusDays(3), created);

        Flyway v19 = Flyway.configure().dataSource(targetDataSource()).load();
        assertEquals(1, v19.migrate().migrationsExecuted);
        Map<UUID, String> states = jdbc.query("select id,status from upload_sessions", rs -> {
            var result = new java.util.HashMap<UUID, String>();
            while (rs.next()) result.put(rs.getObject(1, UUID.class), rs.getString(2));
            return result;
        });
        assertEquals(Map.of(active, "INITIATED", completed, "COMPLETED", expired, "EXPIRED", aborted, "ABORTED"), states);
        assertEquals(created, jdbc.queryForObject("select created_at from upload_sessions where id=?", LocalDateTime.class, completed));
        assertEquals(1, jdbc.queryForObject("select count(*) from upload_sessions where status='COMPLETED' and expires_at <= current_timestamp", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upload_sessions where id=? and " +
                "((status='INITIATED' and expires_at<=current_timestamp) or status='EXPIRED' or " +
                "(status='CLEANING' and updated_at<=current_timestamp - interval '5 minutes'))", Integer.class, completed));
        UUID cleaning = UUID.randomUUID();
        insertSession(owner, cleaning, "CLEANING", LocalDateTime.now().minusDays(3));
        assertEquals("CLEANING", jdbc.queryForObject("select status from upload_sessions where id=?", String.class, cleaning));
        assertEquals(1, jdbc.queryForObject("select count(*) from upload_sessions where status='COMPLETED'", Integer.class));
    }

    private DriverManagerDataSource targetDataSource() {
        String targetUrl = adminUrl.replaceFirst("/[^/?]+(?=[?]|$)", "/" + database);
        return new DriverManagerDataSource(targetUrl, username, password);
    }

    private UUID insertOwner(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email,password_hash,created_at,updated_at) values (?,?,?,?,current_timestamp,current_timestamp)",
                id, "MD007", suffix + "-" + id + "@test.invalid", "test-hash");
        return id;
    }

    private void insertSession(UUID owner, UUID id, String state, LocalDateTime expires) {
        insertSession(owner, id, state, expires, LocalDateTime.now());
    }

    private void insertSession(UUID owner, UUID id, String state, LocalDateTime expires, LocalDateTime timestamp) {
        jdbc.update("insert into upload_sessions(id,owner_id,filename,content_type,total_size_bytes,chunk_size_bytes,total_chunks,status,created_at,updated_at,expires_at,row_version) " +
                        "values (?,?,?,?,1,5,1,?,?,?, ?,0)", id, owner, "file-" + id, "application/octet-stream", state, timestamp, timestamp, expires);
    }
}
