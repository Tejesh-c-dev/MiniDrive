package com.minidrive.storage;

import com.minidrive.exception.SpoolRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationSpoolManagerTest {
    @TempDir Path temp;

    @Test void spoolsWithinLimitsAndReleasesReservation() throws Exception {
        var policy = policy("ok");
        var registry = new SimpleMeterRegistry();
        try (var manager = new ReplicationSpoolManager(policy, registry, true);
             var spool = manager.spool(new ByteArrayInputStream(new byte[]{1,2,3}), 3, sha256())) {
            assertEquals(3, Files.size(spool.path()));
            assertEquals(3, spool.size());
            assertEquals(3, registry.get("minidrive_replication_spool_reserved_bytes").gauge().value());
        }
        try (var manager = new ReplicationSpoolManager(policy, registry, true)) {
            assertEquals(0, registry.get("minidrive_replication_spool_reserved_bytes").gauge().value());
            assertEquals(0, registry.get("minidrive_replication_spool_active").gauge().value());
        }
    }

    @Test void rejectsDeclaredAndActualOversizeAndRemovesPartialFile() throws Exception {
        var p = policy("large"); p.setMaxFileBytes(4); p.setMaxReservedBytes(8);
        try (var manager = new ReplicationSpoolManager(p, new SimpleMeterRegistry(), true)) {
            assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[5]), 5, sha256()));
            assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[5]), 4, sha256()));
            assertEquals(0, spoolCount(p.getDirectory()));
        }
    }

    @Test void releasesAfterInputFailureAndRejectsBelowFreeSpaceReserve() throws Exception {
        var registry = new SimpleMeterRegistry();
        var p = policy("failure");
        try (var manager = new ReplicationSpoolManager(p, registry, true)) {
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("injected input failure"); }
            };
            assertThrows(SpoolRejectedException.class, () -> manager.spool(broken, 2, sha256()));
            assertEquals(0, registry.get("minidrive_replication_spool_active").gauge().value());
            assertEquals(0, spoolCount(p.getDirectory()));
        }
        var diskPolicy = policy("disk"); diskPolicy.setMinFreeBytes(Long.MAX_VALUE);
        try (var manager = new ReplicationSpoolManager(diskPolicy, new SimpleMeterRegistry(), true)) {
            assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[1]), 1, sha256()));
        }
    }

    @Test void releasesReservationAndRemovesPartialFileAfterLocalWriteFailure() throws Exception {
        var p = policy("write-failure"); var registry = new SimpleMeterRegistry();
        var manager = new ReplicationSpoolManager(p, registry, true, path -> new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("injected disk write failure"); }
            @Override public void write(byte[] data, int offset, int length) throws IOException { throw new IOException("injected disk write failure"); }
        });
        try (manager) {
            assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[]{1}), 1, sha256()));
            assertEquals(0, registry.get("minidrive_replication_spool_active").gauge().value());
            assertEquals(0, registry.get("minidrive_replication_spool_reserved_bytes").gauge().value());
            assertEquals(0, spoolCount(p.getDirectory()));
        }
    }

    @Test void aggregateBudgetAndConcurrencyRejectWithoutWaitingAndReleaseAfterSuccess() throws Exception {
        var p = policy("admission"); p.setMaxFileBytes(5); p.setMaxReservedBytes(5); p.setMaxConcurrent(2);
        try (var manager = new ReplicationSpoolManager(p, new SimpleMeterRegistry(), true)) {
            var large = manager.spool(new ByteArrayInputStream(new byte[4]), 4, sha256());
            assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[2]), 2, sha256()));
            large.close();
        }
        var c = policy("concurrency"); c.setMaxConcurrent(1);
        try (var manager = new ReplicationSpoolManager(c, new SimpleMeterRegistry(), true)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            InputStream blocking = new InputStream() {
                private boolean first = true;
                @Override public int read() throws IOException {
                    if (!first) return -1;
                    first = false; entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    return 7;
                }
            };
            var executor = Executors.newSingleThreadExecutor();
            try {
                var task = executor.submit(() -> manager.spool(blocking, 1, sha256()));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(SpoolRejectedException.class, () -> manager.spool(new ByteArrayInputStream(new byte[]{1}), 1, sha256()));
                release.countDown();
                try (var spool = task.get(5, TimeUnit.SECONDS)) { assertEquals(1, spool.size()); }
            } finally { release.countDown(); executor.shutdownNow(); }
            assertEquals(0, spoolCount(c.getDirectory()));
        }
    }

    @Test void startupRecoveryDeletesOnlyOwnedPatternAfterExclusiveLockAndInvalidPolicyFails() throws Exception {
        Path dir = temp.resolve("recovery"); Files.createDirectories(dir);
        Path stale = Files.createFile(dir.resolve("md-repl-abandoned.spool"));
        Path unrelated = Files.createFile(dir.resolve("keep.txt"));
        var p = policy("recovery"); p.setDirectory(dir);
        try (var manager = new ReplicationSpoolManager(p, new SimpleMeterRegistry(), true)) {
            assertFalse(Files.exists(stale)); assertTrue(Files.exists(unrelated));
            assertThrows(IllegalStateException.class, () -> new ReplicationSpoolManager(p, new SimpleMeterRegistry(), true));
        }
        var invalid = policy("invalid"); invalid.setMaxFileBytes(0);
        assertThrows(IllegalStateException.class, invalid::validate);
    }

    private ReplicationSpoolProperties policy(String name) {
        var p = new ReplicationSpoolProperties();
        p.setDirectory(temp.resolve(name));
        p.setMaxFileBytes(1024); p.setMaxReservedBytes(2048); p.setMinFreeBytes(0);
        p.setCapacityCheckIntervalBytes(4);
        return p;
    }

    private static long spoolCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.filter(f -> f.toString().endsWith(".spool")).count(); }
    }

    private static MessageDigest sha256() throws Exception { return MessageDigest.getInstance("SHA-256"); }
}
