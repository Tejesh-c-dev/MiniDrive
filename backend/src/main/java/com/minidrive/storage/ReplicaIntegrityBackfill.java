package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Verifies legacy version objects directly from their recorded storage nodes. */
@Service
public class ReplicaIntegrityBackfill {
    private static final Logger log = LoggerFactory.getLogger(ReplicaIntegrityBackfill.class);
    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final Map<String, NodeStorageClient> nodes;
    private final TransactionTemplate transaction;

    public ReplicaIntegrityBackfill(JdbcTemplate jdbc,
            @Qualifier("storageNodeClients") Map<String, NodeStorageClient> nodes,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Runs a bounded-page, restartable reconciliation pass over durable version objects. */
    public Report run() {
        AtomicLong scanned = new AtomicLong(), verified = new AtomicLong(), failed = new AtomicLong();
        AtomicLong missingLocation = new AtomicLong(), skippedDeleting = new AtomicLong(), secondaryUnverified = new AtomicLong();
        String cursor = "";
        while (true) {
            List<ObjectRow> batch = jdbc.query("select objects.object_key,max(objects.size_bytes),max(l.storage_node_id) from ("
                            + "select object_key,size_bytes,cast(null as varchar(64)) storage_node_id from file_versions "
                            + "union all select object_key,size_bytes,cast(null as varchar(64)) from files "
                            + "union all select l.object_key,cast(null as bigint),l.storage_node_id from object_locations l "
                            + "where not exists(select 1 from file_versions v where v.object_key=l.object_key) "
                            + "and not exists(select 1 from files f where f.object_key=l.object_key) "
                            + "and not exists(select 1 from upload_chunks c where c.staging_object_key=l.object_key)) objects "
                            + "left join object_locations l on l.object_key=objects.object_key "
                            + "where objects.object_key > ? group by objects.object_key order by objects.object_key limit ?",
                    (rs, row) -> new ObjectRow(rs.getString(1), (Long) rs.getObject(2), rs.getString(3)), cursor, BATCH_SIZE);
            if (batch.isEmpty()) break;
            for (ObjectRow object : batch) {
                cursor = object.key();
                scanned.incrementAndGet();
                if (object.size() == null) {
                    failed.incrementAndGet();
                    log.warn("Replica backfill unresolved: key={} reason=storage location has no current file or version metadata", object.key());
                    continue;
                }
                if (object.node() == null) {
                    missingLocation.incrementAndGet();
                    failed.incrementAndGet();
                    log.warn("Replica backfill unresolved: key={} reason=no authoritative object_locations entry", object.key());
                    continue;
                }
                try {
                    Outcome outcome = StorageKeyLock.withKeyLock(jdbc, object.key(), () -> reconcile(object, secondaryUnverified));
                    if (outcome == Outcome.VERIFIED) verified.incrementAndGet();
                    else if (outcome == Outcome.DELETING) skippedDeleting.incrementAndGet();
                    else failed.incrementAndGet();
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    log.error("Replica backfill unresolved: key={} reason={}", object.key(), safeMessage(e));
                }
            }
            log.info("Replica backfill progress: scanned={} verified={} failed={} secondaryUnverified={} missingLocation={} deleting={}",
                    scanned.get(), verified.get(), failed.get(), secondaryUnverified.get(), missingLocation.get(), skippedDeleting.get());
        }
        Report report = new Report(scanned.get(), verified.get(), failed.get(), secondaryUnverified.get(),
                missingLocation.get(), skippedDeleting.get());
        log.info("Replica backfill complete: {}", report);
        return report;
    }

    private Outcome reconcile(ObjectRow object, AtomicLong secondaryUnverified) {
        if (isDeleting(object.key())) return Outcome.DELETING;
        Digest primary;
        try {
            primary = digest(object.node(), object.key());
        } catch (RuntimeException e) {
            persistFailure(object.key(), object.node(), object.size(), safeMessage(e));
            return Outcome.FAILED;
        }

        // A prior verified expectation remains trusted even if a later failure
        // changed its status. Changed bytes must never replace that checksum.
        ReplicaExpectation existing = trusted(object.key(), object.node());
        String checksum = primary.checksum();
        long expectedSize = primary.size();
        if (existing != null) {
            if (existing.size() != primary.size() || !existing.checksum().equalsIgnoreCase(primary.checksum())) {
                persistFailure(object.key(), object.node(), existing.size(), "authoritative bytes differ from previously verified checksum");
                return Outcome.FAILED;
            }
            checksum = existing.checksum();
            expectedSize = existing.size();
        } else if (object.size() != primary.size()) {
            persistFailure(object.key(), object.node(), object.size(), "authoritative byte length differs from file version metadata");
            return Outcome.FAILED;
        }

        String expectedChecksum = checksum;
        long size = expectedSize;
        Map<String, CopyState> copies = new java.util.LinkedHashMap<>();
        for (String node : nodes.keySet()) {
            if (node.equals(object.node())) continue;
            try {
                Digest copy = digest(node, object.key());
                ReplicaExpectation prior = trusted(object.key(), node);
                copies.put(node, copy.size() == size && copy.checksum().equalsIgnoreCase(expectedChecksum)
                        && (prior == null || (prior.size() == copy.size() && prior.checksum().equalsIgnoreCase(copy.checksum())))
                        ? new CopyState(true, null) : new CopyState(false, "replica size or checksum mismatch"));
            } catch (RuntimeException e) {
                copies.put(node, new CopyState(false, safeMessage(e)));
            }
            CopyState observed = copies.get(node);
            if (!observed.healthy()) {
                secondaryUnverified.incrementAndGet();
                log.warn("Replica backfill secondary unresolved: key={} node={} reason={}",
                        object.key(), node, observed.error());
            }
        }
        transaction.executeWithoutResult(status -> {
            if (isDeleting(object.key())) return;
            writeState(object.key(), object.node(), "HEALTHY", size, expectedChecksum, null, true);
            copies.forEach((node, copy) -> writeState(object.key(), node,
                    copy.healthy() ? "HEALTHY" : "FAILED", size, expectedChecksum, copy.error(), copy.healthy()));
        });
        return isDeleting(object.key()) ? Outcome.DELETING : Outcome.VERIFIED;
    }

    private Digest digest(String node, String key) {
        NodeStorageClient client = nodes.get(node);
        if (client == null) throw new StorageException("authoritative storage node is not configured: " + node);
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        long size = 0;
        try (InputStream input = client.download(key)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                sha.update(buffer, 0, count);
                size = Math.addExact(size, count);
            }
        } catch (IOException | ArithmeticException e) {
            throw new StorageException("Unable to read stored bytes for " + key, e);
        }
        return new Digest(size, HexFormat.of().formatHex(sha.digest()));
    }

    private boolean isDeleting(String key) {
        Integer n = jdbc.query("select count(*) from object_replicas where object_key=? and status='DELETING'",
                rs -> rs.next() ? rs.getInt(1) : 0, key);
        return n != null && n > 0;
    }

    private ReplicaExpectation trusted(String key, String node) {
        return jdbc.query("select size_bytes,checksum from object_replicas where object_key=? and storage_node_id=? "
                        + "and verified_at is not null", rs -> {
                    if (!rs.next()) return null;
                    String hash = rs.getString(2);
                    return hash != null && hash.matches("(?i)[0-9a-f]{64}")
                            ? new ReplicaExpectation(rs.getLong(1), hash) : null;
                }, key, node);
    }

    private void persistFailure(String key, String node, long size, String message) {
        transaction.executeWithoutResult(status -> {
            if (isDeleting(key)) return;
            java.util.Set<String> affected = new java.util.LinkedHashSet<>(nodes.keySet());
            affected.add(node);
            for (String affectedNode : affected)
                writeState(key, affectedNode, "FAILED", Math.max(0, size), "", "authoritative verification failed: " + message, false);
        });
    }

    private void writeState(String key, String node, String state, long size, String checksum,
                            String error, boolean verified) {
        // Healthy verified rows are immutable expectations. Failed observations
        // only change state/error and retain any prior trusted checksum.
        boolean preserveVerified = jdbc.query("select verified_at,checksum from object_replicas "
                        + "where object_key=? and storage_node_id=?", rs -> {
                    if (!rs.next() || rs.getTimestamp(1) == null) return false;
                    String oldChecksum = rs.getString(2);
                    return oldChecksum != null && oldChecksum.matches("(?i)[0-9a-f]{64}");
                }, key, node);
        java.sql.Timestamp retry = "HEALTHY".equals(state) ? null
                : java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30));
        int updated = jdbc.update("update object_replicas set status=?, "
                        + "size_bytes=case when ? then size_bytes else ? end, "
                        + "checksum=case when ? then checksum else ? end, "
                        + "attempt_count=attempt_count+1,last_error=?,next_attempt_at=?, "
                        + "verified_at=case when ? then current_timestamp else verified_at end,updated_at=current_timestamp "
                        + "where object_key=? and storage_node_id=? and status <> 'DELETING'",
                state, preserveVerified, size, preserveVerified, checksum, error, retry, verified, key, node);
        if (updated > 0) return;
        try {
            jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,"
                            + "attempt_count,last_error,next_attempt_at,verified_at,updated_at) values (?,?,?,?,?,1,?,?,?,current_timestamp)",
                    key, node, state, size, checksum, error, retry,
                    verified ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
        } catch (DuplicateKeyException concurrentInsert) {
            // Another application instance won the insert; apply the same
            // guarded update without disturbing DELETING or trusted values.
            jdbc.update("update object_replicas set status=?, "
                            + "size_bytes=case when ? then size_bytes else ? end, "
                            + "checksum=case when ? then checksum else ? end, "
                            + "attempt_count=attempt_count+1,last_error=?,next_attempt_at=?, "
                            + "verified_at=case when ? then current_timestamp else verified_at end,updated_at=current_timestamp "
                            + "where object_key=? and storage_node_id=? and status <> 'DELETING'",
                    state, preserveVerified, size, preserveVerified, checksum, error, retry, verified, key, node);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(900, message.length()));
    }

    public record Report(long scanned, long verified, long failed, long secondaryUnverified,
                         long missingLocation, long skippedDeleting) { }
    private record ObjectRow(String key, Long size, String node) { }
    private record Digest(long size, String checksum) { }
    private record ReplicaExpectation(long size, String checksum) { }
    private record CopyState(boolean healthy, String error) { }
    private enum Outcome { VERIFIED, FAILED, DELETING }
}
