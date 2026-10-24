package com.minidrive.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.minidrive.storage.StorageService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded, read-only inventory report for old routed objects that have lost
 * their application references. It intentionally never deletes storage data.
 */
@Service
public class HistoricalObjectOrphanReport {
    private static final Logger log = LoggerFactory.getLogger(HistoricalObjectOrphanReport.class);
    private static final Pattern CONTENT_KEY = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/"
                    + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-[A-Za-z0-9._-]+$");
    private static final Pattern STAGING_KEY = Pattern.compile(
            "^_uploads/[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}/[0-9]+$");

    private static final String CANDIDATES = """
            select l.object_key, l.created_at
              from object_locations l
             where l.created_at <= ?
               and not exists (select 1 from files f where f.object_key = l.object_key)
               and not exists (select 1 from file_versions v where v.object_key = l.object_key)
               and not exists (select 1 from upload_chunks c where c.staging_object_key = l.object_key)
               and not exists (select 1 from folder_deletion_objects d where d.object_key = l.object_key)
             order by l.created_at, l.object_key
            limit ?
            """;

    /* Route rows are only application registrations. These queries deliberately
       do not infer physical existence and never issue a storage mutation. */
    private static final String MISSING_ROUTE_REFERENCES = """
            select refs.object_key from (
                select f.object_key from files f where f.object_key is not null
                union
                select v.object_key from file_versions v where v.object_key is not null
            ) refs left join object_locations l on l.object_key=refs.object_key
            where l.object_key is null order by refs.object_key limit ?
            """;
    private static final String LIVE_REFERENCES = """
            select object_key from (
                select f.object_key from files f where f.object_key is not null
                union
                select v.object_key from file_versions v where v.object_key is not null
            ) refs order by object_key limit ?
            """;
    private static final String STALE_REPLICAS = """
            select r.object_key, r.storage_node_id, r.status from object_replicas r
             where not exists (select 1 from files f where f.object_key=r.object_key)
               and not exists (select 1 from file_versions v where v.object_key=r.object_key)
               and not exists (select 1 from folder_deletion_objects d where d.object_key=r.object_key)
               and r.status <> 'DELETING'
             order by r.updated_at, r.object_key limit ?
            """;
    private static final String STALLED_DELETIONS = """
            select j.id, j.status, j.attempt_count, j.created_at
              from folder_deletion_jobs j
             where j.created_at <= ?
             order by j.created_at limit ?
            """;
    private static final String STALLED_UPLOAD_CLEANUP = """
            select id, status, updated_at from upload_sessions
             where status='CLEANING' and updated_at <= ?
             order by updated_at limit ?
            """;

    private final JdbcTemplate jdbc;
    private final CleanupIntentJournal cleanupJournal;
    private final StorageService storage;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final long gracePeriodDays;
    private final long stalledOperationMinutes = 15;

    public HistoricalObjectOrphanReport(JdbcTemplate jdbc, CleanupIntentJournal cleanupJournal, StorageService storage,
            MeterRegistry metrics,
            @Value("${minidrive.object-gc.report-batch-size:100}") int batchSize,
            @Value("${minidrive.object-gc.orphan-grace-period-days:30}") long gracePeriodDays) {
        this.jdbc = jdbc;
        this.cleanupJournal = cleanupJournal;
        this.storage = storage;
        this.metrics = metrics;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.gracePeriodDays = Math.max(1, Math.min(gracePeriodDays, 3650));
    }

    @Scheduled(fixedDelayString = "${minidrive.object-gc.report-interval-ms:21600000}",
            initialDelayString = "${minidrive.object-gc.report-initial-delay-ms:60000}")
    public void scheduledReport() {
        reportBatch();
    }

    /** One bounded inspection pass, also callable by operations and tests. */
    public int reportBatch() {
        ReconciliationReport report = reconcileBatch();
        return Math.toIntExact(report.count(Status.UNREFERENCED_REGISTERED_OBJECT));
    }

    /**
     * Performs bounded, report-only cross-store checks using database metadata
     * and the storage adapter's side-effect-free existence probe.
     */
    public ReconciliationReport reconcileBatch() {
        java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        CleanupIntentJournal.Inspection journal = cleanupJournal.inspect(batchSize);
        Set<String> pendingIntents = journal.intents().stream().map(CleanupIntentJournal.Intent::objectKey)
                .collect(Collectors.toSet());
        for (CleanupIntentJournal.Issue issue : journal.issues())
            findings.add(new Finding(Status.INSPECTION_FAILED, issue.detail(), "cleanup-journal/" + issue.reference()));
        if (journal.limitReached()) findings.add(new Finding(Status.PENDING_OPERATION,
                "cleanup journal inventory reached the configured report batch limit",
                "cleanup-journal/" + cleanupJournal.directoryName()));
        for (String key : pendingIntents) {
            findings.add(new Finding(Status.PENDING_OPERATION,
                    "independent cleanup intent is awaiting idempotent deletion replay", key));
        }
        final List<Candidate> candidates;
        try {
            candidates = jdbc.query(CANDIDATES,
                    (rs, row) -> new Candidate(rs.getString("object_key"), rs.getTimestamp("created_at").toLocalDateTime()),
                    LocalDateTime.now().minusDays(gracePeriodDays), batchSize);
        } catch (RuntimeException databaseUnavailable) {
            metrics.counter("minidrive_object_gc_total", "outcome", "failure").increment();
            log.warn("Historical object orphan report deferred because its reference inventory could not be queried",
                    databaseUnavailable);
            return new ReconciliationReport(List.of(new Finding(Status.INSPECTION_FAILED,
                    "database reference inventory unavailable", null)));
        }

        metrics.counter("minidrive_object_gc_total", "outcome", "discovered").increment(candidates.size());
        for (Candidate candidate : candidates) {
            if (pendingIntents.contains(candidate.objectKey())) {
                metrics.counter("minidrive_object_gc_total", "outcome", "retained").increment();
                continue;
            }
            if (!CONTENT_KEY.matcher(candidate.objectKey()).matches()
                    && !STAGING_KEY.matcher(candidate.objectKey()).matches()) {
                metrics.counter("minidrive_object_gc_total", "outcome", "retained").increment();
                log.warn("Aged unreferenced route has an unknown namespace and was retained objectKey={}",
                        candidate.objectKey());
                continue;
            }
            // A route row surviving every authoritative application reference is
            // an investigation candidate, not proof of physical-store state.
            // All modes are report-only until Silo inventory and lifecycle locks
            // can establish stronger evidence for safe reclamation.
            metrics.counter("minidrive_object_gc_total", "outcome", "report_only").increment();
            log.warn("Aged unreferenced object route found for operator review objectKey={} registeredAt={}",
                    candidate.objectKey(), candidate.registeredAt());
            findings.add(new Finding(Status.UNREFERENCED_REGISTERED_OBJECT,
                    "aged route has no live metadata, upload chunk, deletion manifest, or cleanup intent",
                    candidate.objectKey()));
        }
        queryFindings(findings, MISSING_ROUTE_REFERENCES, Status.MISSING_ROUTE_REGISTRATION,
                "live file/version reference has no object_locations registration", "object_key", batchSize);
        inspectLiveReferences(findings);
        queryFindings(findings, STALE_REPLICAS, Status.STALE_REPLICA_STATE,
                "replica row has no live metadata or pending deletion manifest", "object_key", batchSize);
        findings.removeIf(f -> f.status() == Status.STALE_REPLICA_STATE && pendingIntents.contains(f.reference()));
        queryFindings(findings, STALLED_DELETIONS, Status.PENDING_OPERATION,
                "folder/file deletion job is older than the stalled-operation threshold", "id",
                LocalDateTime.now().minusMinutes(stalledOperationMinutes), batchSize);
        queryFindings(findings, STALLED_UPLOAD_CLEANUP, Status.PENDING_OPERATION,
                "upload cleanup remains in CLEANING beyond the worker lease threshold", "id",
                LocalDateTime.now().minusMinutes(stalledOperationMinutes), batchSize);
        for (Finding finding : findings) log.warn("Cross-store reconciliation status={} detail={} reference={}",
                finding.status(), finding.detail(), finding.reference());
        return new ReconciliationReport(List.copyOf(findings));
    }

    private void queryFindings(List<Finding> findings, String sql, Status status, String detail,
                               String referenceColumn, Object... params) {
        try {
            jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler)
                    rs -> findings.add(new Finding(status, detail, rs.getString(referenceColumn))), params);
        } catch (RuntimeException failure) {
            findings.add(new Finding(Status.INSPECTION_FAILED,
                    "database inspection failed for " + status.name(), null));
            log.warn("Cross-store reconciliation inspection failed status={}", status, failure);
        }
    }

    private void inspectLiveReferences(List<Finding> findings) {
        final List<String> keys;
        try {
            keys = jdbc.query(LIVE_REFERENCES, (rs, row) -> rs.getString("object_key"), batchSize);
        } catch (RuntimeException failure) {
            findings.add(new Finding(Status.INSPECTION_FAILED,
                    "database inspection failed for live object references", null));
            log.warn("Cross-store reconciliation could not load live object references", failure);
            return;
        }
        for (String key : keys) {
            try {
                java.util.Optional<Boolean> exists = storage.exists(key);
                if (exists.isEmpty()) {
                    findings.add(new Finding(Status.INSPECTION_FAILED,
                            "storage adapter does not support metadata-only existence inspection", key));
                } else if (!exists.get()) {
                    findings.add(new Finding(Status.MISSING_OBJECT,
                            "live file/version reference is absent from its authoritative registered storage node", key));
                }
            } catch (RuntimeException unavailable) {
                findings.add(new Finding(Status.INSPECTION_FAILED,
                        "authoritative storage node could not be inspected", key));
            }
        }
    }

    public enum Status {
        MISSING_OBJECT, MISSING_ROUTE_REGISTRATION, UNREFERENCED_REGISTERED_OBJECT,
        STALE_REPLICA_STATE, PENDING_OPERATION, INSPECTION_FAILED
    }
    public record Finding(Status status, String detail, String reference) { }
    public record ReconciliationReport(List<Finding> findings) {
        public long count(Status status) { return findings.stream().filter(f -> f.status() == status).count(); }
    }

    private record Candidate(String objectKey, LocalDateTime registeredAt) { }
}
