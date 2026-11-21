package com.minidrive.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in V21 recovery tests against disposable PostgreSQL databases only. */
@EnabledIfSystemProperty(named = "minidrive.live-refresh-token-migration", matches = "true")
class RefreshTokenMigrationPostgresLiveTest {
    private static final String ADMIN_URL = System.getProperty("minidrive.live-refresh-token-migration.admin-url");
    private static final String USER = System.getProperty("minidrive.live-refresh-token-migration.db-user", "md_it");
    private static final String PASSWORD = System.getProperty("minidrive.live-refresh-token-migration.db-password");
    private static final String DATABASE_PREFIX = "md_v21_";

    private final String database = DATABASE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private JdbcTemplate jdbc;

    @BeforeEach
    void createDisposableDatabase() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/postgres", ADMIN_URL,
                "Migration tests may create databases only on isolated PostgreSQL at 127.0.0.1:55439");
        assertEquals("md_it", USER);
        assertNotNull(PASSWORD);
        assertNotEquals("minidrive", PASSWORD);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(ADMIN_URL, USER, PASSWORD));
        admin.execute("CREATE DATABASE " + database);
        jdbc = new JdbcTemplate(targetDataSource());
    }

    @AfterEach
    void dropOnlyDisposableDatabase() {
        if (ADMIN_URL == null || jdbc == null) return;
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(ADMIN_URL, USER, PASSWORD));
        admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='" + database + "'");
        admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void freshDatabaseCreatesRefreshTokenSchema() {
        Flyway flyway = Flyway.configure().dataSource(targetDataSource()).load();

        assertEquals(21, flyway.migrate().migrationsExecuted);
        assertRefreshTokenSchema();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='21' AND success", Integer.class));
    }

    @Test
    void matchingProvisionedObjectsAreReused() {
        migrateThroughV20();
        provisionExpectedObjects();

        assertEquals(1, Flyway.configure().dataSource(targetDataSource()).load().migrate().migrationsExecuted);
        assertRefreshTokenSchema();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='21' AND success", Integer.class));
    }

    @Test
    void partiallyProvisionedTableReceivesMissingColumnsConstraintsAndIndexes() {
        migrateThroughV20();
        jdbc.execute("CREATE TABLE refresh_tokens (token_hash CHAR(64) NOT NULL, family_id UUID NOT NULL)");
        jdbc.execute("CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id)");

        assertEquals(1, Flyway.configure().dataSource(targetDataSource()).load().migrate().migrationsExecuted);
        assertRefreshTokenSchema();
    }

    @Test
    void incompatibleColumnTypeFailsWithoutRecordingV21() {
        migrateThroughV20();
        jdbc.execute("CREATE TABLE refresh_tokens (token_hash VARCHAR(64) NOT NULL)");

        assertMigrationRejected("incompatible column public.refresh_tokens.token_hash");
        assertEquals("character varying(64)", jdbc.queryForObject(
                "SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='refresh_tokens'::regclass AND attname='token_hash'", String.class));
    }

    @Test
    void incompatibleColumnDefaultFailsClosed() {
        migrateThroughV20();
        provisionExpectedObjects();
        jdbc.execute("ALTER TABLE refresh_tokens ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP + INTERVAL '1 minute'");

        assertMigrationRejected("incompatible column public.refresh_tokens.created_at");
    }

    @Test
    void incompatiblePrimaryKeyFailsClosed() {
        migrateThroughV20();
        provisionExpectedObjects();
        jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_pkey");
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (family_id)");

        assertMigrationRejected("incompatible constraint public.refresh_tokens.refresh_tokens_pkey");
    }

    @Test
    void incompatibleForeignKeyFailsClosed() {
        migrateThroughV20();
        provisionExpectedObjects();
        jdbc.execute("ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_user_id_fkey");
        jdbc.execute("ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_user_id_fkey " +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT");

        assertMigrationRejected("incompatible constraint public.refresh_tokens.refresh_tokens_user_id_fkey");
    }

    @Test
    void incompatibleIndexFailsClosed() {
        migrateThroughV20();
        provisionExpectedObjects();
        jdbc.execute("DROP INDEX idx_refresh_tokens_family_id");
        jdbc.execute("CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(user_id)");

        assertMigrationRejected("incompatible index public.idx_refresh_tokens_family_id");
    }

    private void migrateThroughV20() {
        Flyway.configure().dataSource(targetDataSource()).target(MigrationVersion.fromVersion("20")).load().migrate();
    }

    private void provisionExpectedObjects() {
        jdbc.execute("CREATE TABLE refresh_tokens (" +
                "token_hash CHAR(64) PRIMARY KEY, family_id UUID NOT NULL, user_id UUID NOT NULL " +
                "REFERENCES users(id) ON DELETE CASCADE, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, " +
                "revoked_at TIMESTAMP WITH TIME ZONE, replaced_by_hash CHAR(64), " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id)");
        jdbc.execute("CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id)");
        jdbc.execute("CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at)");
    }

    private void assertRefreshTokenSchema() {
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='refresh_tokens'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='refresh_tokens'::regclass AND contype='p' AND pg_get_constraintdef(oid)='PRIMARY KEY (token_hash)'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='refresh_tokens'::regclass AND contype='f' AND pg_get_constraintdef(oid)='FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE' AND convalidated", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='refresh_tokens' AND column_name='created_at' AND is_nullable='NO' AND column_default='CURRENT_TIMESTAMP'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='refresh_tokens' AND indexname IN ('idx_refresh_tokens_family_id','idx_refresh_tokens_user_id','idx_refresh_tokens_expires_at')", Integer.class));
    }

    private void assertMigrationRejected(String diagnostic) {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> Flyway.configure().dataSource(targetDataSource()).load().migrate());
        assertTrue(failure.toString().contains(diagnostic), failure.toString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='21'", Integer.class));
    }

    private DriverManagerDataSource targetDataSource() {
        String targetUrl = ADMIN_URL.replaceFirst("/[^/?]+(?=[?]|$)", "/" + database);
        return new DriverManagerDataSource(targetUrl, USER, PASSWORD);
    }
}
