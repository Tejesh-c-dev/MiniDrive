package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes objects to the authoritative node and, when enabled, maintains
 * verified copies on the configured secondary nodes.
 */
@Service
public class RoutingStorageService implements StorageService {
    private final JdbcTemplate jdbc;
    private final Map<String, NodeStorageClient> nodes;
    private final StorageNodeSelector selector;
    private final MeterRegistry metrics;
    private final boolean replicationEnabled;
    private final String primaryNodeId;
    private final Set<String> repairs = ConcurrentHashMap.newKeySet();
    private TransactionTemplate deletionIntentTransaction;
    private final Map<String, AtomicInteger> nodeHealth = new ConcurrentHashMap<>();

    @Autowired
    public RoutingStorageService(
            JdbcTemplate jdbc,
            @Qualifier("storageNodeClients") Map<String, NodeStorageClient> nodes,
            StorageNodeSelector selector,
            MeterRegistry metrics,
            @Value("${minidrive.storage.replication.enabled:false}") boolean replicationEnabled,
            @Value("${minidrive.storage.replication.primary-node-id:primary}") String primaryNodeId) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.selector = selector;
        this.metrics = metrics;
        this.replicationEnabled = replicationEnabled;
        this.primaryNodeId = primaryNodeId;
        nodes.keySet().forEach(nodeId -> {
            AtomicInteger status = new AtomicInteger(-1);
            nodeHealth.put(nodeId, status);
            metrics.gauge("minidrive_storage_node_health",
                    io.micrometer.core.instrument.Tags.of("node_id", nodeId), status);
        });
        if (replicationEnabled && !nodes.containsKey(primaryNodeId)) {
            throw new IllegalStateException("Configured replication primary is not enabled: " + primaryNodeId);
        }
        if (replicationEnabled) {
            validateReplicationEnablement();
        }
    }

    private void validateReplicationEnablement() {
        var keys = jdbc.query("select o.object_key, l.storage_node_id from "
                        + "(select object_key from file_versions union select object_key from files) o "
                        + "left join object_locations l on l.object_key=o.object_key order by o.object_key",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2)});
        for (String[] row : keys) {
            if (row[1] == null || !hasVerifiedExpectation(row[0], row[1])) {
                throw new IllegalStateException("Replication cannot be enabled: object integrity backfill is incomplete for " + row[0]
                        + ". Run with MINIDRIVE_REPLICATION_BACKFILL=true while replication is disabled.");
            }
        }
    }

    private boolean hasVerifiedExpectation(String key, String node) {
        String checksum = jdbc.query("select checksum from object_replicas where object_key=? and storage_node_id=? "
                        + "and status='HEALTHY' and verified_at is not null",
                rs -> rs.next() ? rs.getString(1) : null, key, node);
        if (checksum == null || !checksum.matches("(?i)[0-9a-f]{64}")) return false;
        Integer deleting = jdbc.query("select count(*) from object_replicas where object_key=? and status='DELETING'",
                rs -> rs.next() ? rs.getInt(1) : 0, key);
        return deleting == null || deleting == 0;
    }

    /** Compatibility constructor used by unit tests and non-Spring callers. */
    public RoutingStorageService(JdbcTemplate jdbc, Map<String, NodeStorageClient> nodes,
                                 StorageNodeSelector selector, MeterRegistry metrics) {
        this(jdbc, nodes, selector, metrics, false, "primary");
    }

    public RoutingStorageService(JdbcTemplate jdbc, Map<String, NodeStorageClient> nodes,
                                 StorageNodeSelector selector) {
        this(jdbc, nodes, selector, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        deletionIntentTransaction = new TransactionTemplate(transactionManager);
        deletionIntentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private String existing(String key) {
        return jdbc.query("select storage_node_id from object_locations where object_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
    }

    private NodeStorageClient client(String id) {
        NodeStorageClient client = nodes.get(id);
        if (client == null) throw new StorageException("Storage node required for an object is not configured: " + id);
        return client;
    }

    @Override
    public void upload(String key, InputStream input, long size, String type) {
        if (!replicationEnabled) {
            uploadLegacy(key, input, size, type);
            return;
        }
        Path staged = null;
        try {
            staged = Files.createTempFile("minidrive-replication-", ".object");
            MessageDigest digest = sha256();
            long copied = copy(input, Files.newOutputStream(staged), digest);
            if (copied != size) throw new StorageException("Uploaded object size does not match metadata");
            String checksum = HexFormat.of().formatHex(digest.digest());
            String primary = primaryNodeId;
            upsertLocation(key, primary);
            uploadAndRecord(key, staged, primary, size, type, checksum, "primary");
            for (String nodeId : nodes.keySet()) {
                if (!nodeId.equals(primary)) replicate(key, staged, nodeId, size, type, checksum);
            }
        } catch (IOException e) {
            throw new StorageException("Unable to stage uploaded file", e);
        } finally {
            deleteTemp(staged);
        }
    }

    private void uploadLegacy(String key, InputStream input, long size, String type) {
        String location = existing(key);
        if (location == null) {
            String selected = selector.select(key, nodes.keySet());
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?,?) "
                    + "on conflict (object_key) do nothing", key, selected);
            location = existing(key);
        }
        String nodeId = location;
        NodeStorageClient node = client(nodeId);
        measure(nodeId, "upload", () -> {
            node.upload(key, input, size, type);
            metrics.counter("minidrive_storage_node_bytes_total", "operation", "upload", "node_id", nodeId).increment(size);
            return null;
        });
    }

    @Override
    public InputStream download(String key) {
        if (!replicationEnabled) {
            String location = existing(key);
            if (location == null) throw new StorageException("No storage location registered for object " + key);
            return timedDownload(location, client(location), key);
        }
        ReplicaExpectation expected = expectation(key);
        String primary = existing(key);
        if (primary == null) throw new StorageException("No storage location registered for object " + key);
        try {
            return validatedDownload(key, primary, expected);
        } catch (RuntimeException primaryFailure) {
            metrics.counter("minidrive_storage_read_fallback_total", "outcome", "attempted").increment();
            for (String nodeId : nodes.keySet()) {
                if (nodeId.equals(primary) || !isHealthy(key, nodeId)) continue;
                try {
                    InputStream result = validatedDownload(key, nodeId, expected);
                    metrics.counter("minidrive_storage_read_fallback_total", "outcome", "success").increment();
                    return result;
                } catch (RuntimeException ignored) {
                    // The next verified copy is the only safe alternative.
                }
            }
            throw new StorageException("No valid storage copy is available for object " + key, primaryFailure);
        }
    }

    @Override
    public void delete(String key) {
        withKeyLock(key, () -> { deleteLocked(key); return null; });
    }

    private void deleteLocked(String key) {
        String primary = existing(key);
        Set<String> targets = new java.util.LinkedHashSet<>();
        if (primary != null) targets.add(primary);
        // A failed upload transaction can roll back object_locations after the
        // object reached storage. Reconstruct only that upload's stable route;
        // in replicated mode an unregistered key may exist on any configured node.
        else if (replicationEnabled) targets.addAll(nodes.keySet());
        else if (!nodes.isEmpty()) targets.add(selector.select(key, nodes.keySet()));
        var replicaNodes = jdbc.query("select storage_node_id from object_replicas where object_key=?",
                (rs, row) -> rs.getString(1), key);
        if (replicaNodes != null) targets.addAll(replicaNodes);
        if (replicationEnabled && primary != null) targets.addAll(nodes.keySet());
        if (targets.isEmpty()) return;

        // Persist a tombstone before touching storage. A process restart or a
        // scheduled repair must not mistake a partly deleted object for a
        // damaged replica and recreate it from another node.
        ReplicaExpectation expected = expectation(key);
        Runnable recordIntent = () -> {
            for (String nodeId : targets) mark(key, nodeId, "DELETING", expected, null, "deletion pending");
        };
        if (deletionIntentTransaction == null) recordIntent.run();
        else deletionIntentTransaction.executeWithoutResult(status -> recordIntent.run());

        RuntimeException failure = null;
        for (String nodeId : targets) {
            try {
                measure(nodeId, "delete", () -> { client(nodeId).delete(key); return null; });
            } catch (RuntimeException nodeFailure) {
                jdbc.update("update object_replicas set last_error=?,updated_at=current_timestamp "
                                + "where object_key=? and storage_node_id=? and status='DELETING'",
                        nodeFailure.getMessage(), key, nodeId);
                if (failure == null) failure = nodeFailure;
                else failure.addSuppressed(nodeFailure);
            }
        }
        // Keep every location/replica reference while any physical deletion is
        // uncertain. The durable caller can retry this key after storage recovers.
        if (failure != null) throw failure;
        jdbc.update("delete from object_locations where object_key=?", key);
        jdbc.update("delete from object_replicas where object_key=?", key);
    }

    /** Runs one bounded repair pass; exposed for deterministic tests and operations. */
    public int repairOnce() {
        if (!replicationEnabled) return 0;
        var rows = jdbc.query("select object_key, storage_node_id from object_replicas "
                        + "where status in ('PENDING','FAILED') and (next_attempt_at is null or next_attempt_at <= current_timestamp) "
                        + "order by updated_at limit 100",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2)});
        int repaired = 0;
        for (String[] row : rows) if (repairs.add(row[0] + "\0" + row[1])) {
            try {
                if (withKeyLock(row[0], () -> heal(row[0], row[1]))) repaired++;
            } finally { repairs.remove(row[0] + "\0" + row[1]); }
        }
        return repaired;
    }

    @Scheduled(fixedDelayString = "${minidrive.storage.replication.repair-delay-ms:30000}")
    public void scheduledRepair() { repairOnce(); }

    /** Refreshes bounded per-node availability gauges without exposing object data. */
    @Scheduled(fixedDelayString = "${minidrive.storage.health-check-delay-ms:15000}")
    public void probeStorageNodes() {
        nodes.forEach((nodeId, node) -> nodeHealth.get(nodeId).set(node.isHealthy() ? 1 : 0));
    }

    private boolean heal(String key, String target) {
        if (isDeleting(key) || !isRepairPending(key, target)) return false;
        ReplicaExpectation expected = expectation(key);
        String source = healthySource(key, target);
        if (source == null) { markFailure(key, target, new StorageException("No healthy source for repair")); return false; }
        Path staged = null;
        try {
            mark(key, target, "PENDING", expected, null, null);
            staged = Files.createTempFile("minidrive-heal-", ".object");
            try (InputStream in = validatedDownload(key, source, expected);
                 OutputStream out = Files.newOutputStream(staged)) {
                in.transferTo(out);
            }
            uploadAndRecord(key, staged, target, expected.size(), "application/octet-stream",
                    expected.checksum(), "healing");
            metrics.counter("minidrive_replication_healing_total", "outcome", "success").increment();
            return true;
        } catch (RuntimeException | IOException e) {
            markFailure(key, target, e);
            metrics.counter("minidrive_replication_healing_total", "outcome", "failure").increment();
            return false;
        } finally { deleteTemp(staged); }
    }

    private void replicate(String key, Path staged, String node, long size, String type, String checksum) {
        try {
            mark(key, node, "PENDING", new ReplicaExpectation(size, checksum), null, null);
            uploadAndRecord(key, staged, node, size, type, checksum, "replication");
        } catch (RuntimeException e) { markFailure(key, node, e); }
    }

    private void uploadAndRecord(String key, Path staged, String nodeId, long size, String type,
                                 String checksum, String operation) {
        try (InputStream in = Files.newInputStream(staged)) {
            measure(nodeId, "upload", () -> { client(nodeId).upload(key, in, size, type); return null; });
            verifyNode(nodeId, key, size, checksum);
            mark(key, nodeId, "HEALTHY", new ReplicaExpectation(size, checksum), null, null);
            metrics.counter("minidrive_replication_attempts_total", "operation", operation, "outcome", "success").increment();
        } catch (IOException e) {
            throw new StorageException("Unable to read staged object", e);
        } catch (RuntimeException e) {
            metrics.counter("minidrive_replication_attempts_total", "operation", operation, "outcome", "failure").increment();
            throw e;
        }
    }

    private InputStream validatedDownload(String key, String nodeId, ReplicaExpectation expected) {
        Path staged = null;
        try {
            staged = Files.createTempFile("minidrive-download-", ".object");
            MessageDigest digest = sha256();
            long size;
            try (InputStream in = timedDownload(nodeId, client(nodeId), key);
                 OutputStream out = Files.newOutputStream(staged)) {
                size = copy(in, out, digest);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (size != expected.size() || !checksum.equalsIgnoreCase(expected.checksum())) {
                mark(key, nodeId, "FAILED", expected, null, "checksum or size mismatch");
                deleteTemp(staged);
                throw new StorageException("Stored object integrity check failed");
            }
            mark(key, nodeId, "HEALTHY", expected, null, null);
            Path result = staged;
            staged = null;
            return deletingStream(Files.newInputStream(result), result);
        } catch (IOException e) {
            deleteTemp(staged);
            throw new StorageException("Unable to validate stored object", e);
        } catch (RuntimeException e) {
            deleteTemp(staged);
            throw e;
        }
    }

    private void verifyNode(String nodeId, String key, long size, String checksum) {
        ReplicaExpectation expected = new ReplicaExpectation(size, checksum);
        try (InputStream ignored = validatedDownload(key, nodeId, expected)) {
            // Validation is complete before the metadata is marked healthy.
        } catch (IOException e) { throw new StorageException("Unable to verify replica", e); }
    }

    private ReplicaExpectation expectation(String key) {
        return jdbc.query("select size_bytes, checksum from object_replicas "
                        + "where object_key=? and status='HEALTHY' limit 1",
                rs -> rs.next() ? new ReplicaExpectation(rs.getLong(1), rs.getString(2)) : null, key);
    }

    private boolean isHealthy(String key, String node) {
        Integer count = jdbc.query("select count(*) from object_replicas where object_key=? and storage_node_id=? and status='HEALTHY'",
                rs -> rs.next() ? rs.getInt(1) : 0, key, node);
        return count != null && count > 0;
    }

    private boolean isDeleting(String key) {
        Integer count = jdbc.query("select count(*) from object_replicas where object_key=? and status='DELETING'",
                rs -> rs.next() ? rs.getInt(1) : 0, key);
        return count != null && count > 0;
    }

    private boolean isRepairPending(String key, String node) {
        Integer count = jdbc.query("select count(*) from object_replicas "
                        + "where object_key=? and storage_node_id=? and status in ('PENDING','FAILED')",
                rs -> rs.next() ? rs.getInt(1) : 0, key, node);
        return count != null && count > 0;
    }

    /**
     * In-process locking avoids needless database round trips for H2-backed
     * tests. PostgreSQL advisory locks also serialize workers in separate app
     * instances, and are session-scoped so they cover the storage I/O itself.
     */
    private <T> T withKeyLock(String key, java.util.function.Supplier<T> action) {
        return StorageKeyLock.withKeyLock(jdbc, key, action);
    }

    private String healthySource(String key, String target) {
        return jdbc.query("select storage_node_id from object_replicas where object_key=? and storage_node_id<>? "
                        + "and status='HEALTHY' limit 1", rs -> rs.next() ? rs.getString(1) : null, key, target);
    }

    private void markFailure(String key, String node, Throwable error) {
        mark(key, node, "FAILED", expectation(key), null, error.getMessage());
    }

    private void mark(String key, String node, String status, ReplicaExpectation expected,
                      RuntimeException error, String message) {
        if (expected == null) expected = new ReplicaExpectation(0, "");
        int updated = jdbc.update("update object_replicas set status=?,size_bytes=?,checksum=?,"
                + "attempt_count=attempt_count+1,last_error=?,next_attempt_at=?,verified_at=?,updated_at=current_timestamp "
                + "where object_key=? and storage_node_id=?",
                status, expected.size(), expected.checksum(), message,
                "HEALTHY".equals(status) ? null : java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30)),
                "HEALTHY".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null, key, node);
        if (updated == 0) {
            jdbc.update("insert into object_replicas(object_key,storage_node_id,status,size_bytes,checksum,"
                    + "attempt_count,last_error,next_attempt_at,verified_at,updated_at) values (?,?,?,?,?,1,?,?,?,current_timestamp)",
                    key, node, status, expected.size(), expected.checksum(), message,
                    "HEALTHY".equals(status) ? null : java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30)),
                    "HEALTHY".equals(status) ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
        }
    }

    private void upsertLocation(String key, String node) {
        if (jdbc.update("update object_locations set storage_node_id=? where object_key=?", node, key) == 0) {
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?,?)", key, node);
        }
    }

    private InputStream deletingStream(InputStream input, Path path) {
        return new FilterInputStream(input) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { deleteTemp(path); }
            }
        };
    }

    private InputStream timedDownload(String nodeId, NodeStorageClient node, String key) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            InputStream stream = node.download(key);
            return new FilterInputStream(stream) {
                private boolean done;
                private void finish(String outcome) {
                    if (!done) { done = true; sample.stop(metrics.timer("minidrive_storage_node_operation_seconds",
                            "operation", "download", "outcome", outcome, "node_id", nodeId)); }
                }
                private void bytes(long n) { if (n > 0) metrics.counter("minidrive_storage_node_bytes_total",
                        "operation", "download", "node_id", nodeId).increment(n); }
                @Override public int read() throws IOException { try { int n=super.read(); if(n<0)finish("success");else bytes(1);return n; } catch(IOException|RuntimeException e){finish("failure");throw e;} }
                @Override public int read(byte[] b,int o,int l)throws IOException { try { int n=super.read(b,o,l);if(n<0)finish("success");else bytes(n);return n;}catch(IOException|RuntimeException e){finish("failure");throw e;} }
                @Override public void close() throws IOException { try {super.close();} finally {if(!done)finish("failure");} }
            };
        } catch (RuntimeException e) {
            sample.stop(metrics.timer("minidrive_storage_node_operation_seconds", "operation", "download", "outcome", "failure", "node_id", nodeId));
            throw e;
        }
    }

    private <T> T measure(String nodeId, String operation, Callable<T> call) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "success";
        try { return call.call(); }
        catch (RuntimeException e) { outcome = "failure"; throw e; }
        catch (Exception e) { outcome = "failure"; throw new StorageException("Storage operation failed", e); }
        finally { sample.stop(metrics.timer("minidrive_storage_node_operation_seconds",
                "operation", operation, "outcome", outcome, "node_id", nodeId)); }
    }

    private static long copy(InputStream in, OutputStream out, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[8192]; long total = 0; int read;
        while ((read = in.read(buffer)) != -1) { out.write(buffer, 0, read); digest.update(buffer, 0, read); total += read; }
        out.close(); return total;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static void deleteTemp(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private record ReplicaExpectation(long size, String checksum) { }
}
