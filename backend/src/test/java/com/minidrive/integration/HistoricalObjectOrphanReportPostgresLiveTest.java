package com.minidrive.integration;

import com.minidrive.service.CleanupIntentJournal;
import com.minidrive.service.HistoricalObjectOrphanReport;
import com.minidrive.storage.StorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies that a genuine PostgreSQL inspection error is inconclusive rather
 * than an empty reference inventory. The database is disposable and the
 * persistent development database is never modified.
 */
@EnabledIfSystemProperty(named = "minidrive.live-pg-reconciliation", matches = "true")
class HistoricalObjectOrphanReportPostgresLiveTest {
    private final String adminUrl = System.getProperty("minidrive.live-pg-reconciliation.admin-url");
    private final String username = System.getProperty("minidrive.live-pg-reconciliation.db-user", "md007");
    private final String password = System.getProperty("minidrive.live-pg-reconciliation.db-password");
    private final String database = "md009_report_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private JdbcTemplate jdbc;

    @BeforeEach
    void createDisposableDatabase() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/postgres", adminUrl,
                "Report test may create/drop databases only on disposable integration PostgreSQL");
        assertEquals("md_it", username);
        assertNotEquals("minidrive", password);
        assertTrue(adminUrl != null && !adminUrl.isBlank(), "Supply the isolated PostgreSQL admin URL");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, username, password));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource target = new DriverManagerDataSource(targetUrl(), username, password);
        Flyway.configure().dataSource(target).baselineOnMigrate(true).load().migrate();
        jdbc = new JdbcTemplate(target);
    }

    @AfterEach
    void dropOnlyDisposableDatabase() {
        if (adminUrl == null || adminUrl.isBlank()) return;
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, username, password));
        admin.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='" + database + "'");
        admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void failedReferenceQueryIsInspectionFailureAndDoesNotInspectOrMutateStorage() throws Exception {
        jdbc.execute("DROP TABLE object_locations");
        StorageService storage = mock(StorageService.class);
        Path journalPath = Files.createTempDirectory("md009-report-journal-");
        HistoricalObjectOrphanReport report = new HistoricalObjectOrphanReport(
                jdbc, new CleanupIntentJournal(journalPath.toString()), storage,
                new SimpleMeterRegistry(), 100, 30);

        HistoricalObjectOrphanReport.ReconciliationReport result = report.reconcileBatch();

        assertEquals(1, result.count(HistoricalObjectOrphanReport.Status.INSPECTION_FAILED));
        assertTrue(result.findings().stream().anyMatch(f ->
                f.detail().contains("database reference inventory unavailable")));
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    private String targetUrl() {
        return adminUrl.replaceFirst("/[^/?]+(?=[?]|$)", "/" + database);
    }
}
