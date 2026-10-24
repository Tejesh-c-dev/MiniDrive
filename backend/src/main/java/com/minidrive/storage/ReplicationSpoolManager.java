package com.minidrive.storage;

import com.minidrive.exception.SpoolRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local admission control plus an exclusively owned, crash-recoverable spool directory. */
public final class ReplicationSpoolManager implements AutoCloseable {
    @FunctionalInterface
    interface OutputFactory {
        OutputStream open(Path path) throws IOException;
    }
    private static final Logger log = LoggerFactory.getLogger(ReplicationSpoolManager.class);
    private final ReplicationSpoolProperties policy;
    private final MeterRegistry metrics;
    private final Path directory;
    private final OutputFactory outputFactory;
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong reserved = new AtomicLong();
    private final ConcurrentMap<Path, Long> pendingCleanup = new ConcurrentHashMap<>();

    public ReplicationSpoolManager(ReplicationSpoolProperties policy, MeterRegistry metrics, boolean enabled) {
        this(policy, metrics, enabled, path -> Files.newOutputStream(path, StandardOpenOption.WRITE));
    }

    ReplicationSpoolManager(ReplicationSpoolProperties policy, MeterRegistry metrics, boolean enabled,
                            OutputFactory outputFactory) {
        this.policy = policy;
        this.metrics = metrics;
        this.outputFactory = outputFactory;
        policy.validate();
        metrics.gauge("minidrive_replication_spool_active", active);
        metrics.gauge("minidrive_replication_spool_reserved_bytes", reserved);
        if (!enabled) { directory = null; ownerChannel = null; ownerLock = null; return; }
        Path resolvedDirectory;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(policy.getDirectory());
            resolvedDirectory = policy.getDirectory().toRealPath();
            if (!Files.isDirectory(resolvedDirectory) || !Files.isWritable(resolvedDirectory)) throw new IOException("not a writable directory");
            Path lockPath = resolvedDirectory.resolve(".minidrive-replication-spool.lock");
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("directory is already in use by this process", e); }
            if (lock == null) throw new IOException("directory is already owned by another application instance");
            recoverAbandoned(resolvedDirectory);
        } catch (IOException e) {
            if (lock != null) try { lock.release(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            if (channel != null) try { channel.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw new IllegalStateException("Replication spool directory is unavailable or already owned; configure an exclusive writable directory", e);
        }
        directory = resolvedDirectory;
        ownerChannel = channel;
        ownerLock = lock;
    }

    public Spool spool(InputStream input, long expectedSize, MessageDigest digest) {
        if (input == null) {
            throw new SpoolRejectedException(SpoolRejectedException.Reason.UNAVAILABLE,
                    "Replication upload input is unavailable");
        }
        try { return spoolOwned(input, expectedSize, digest); }
        finally {
            try { input.close(); }
            catch (IOException e) {
                metrics.counter("minidrive_replication_spool_io_failures_total", "kind", "input_close").increment();
                log.warn("Unable to close replication upload input stream", e);
            }
        }
    }

    private Spool spoolOwned(InputStream input, long expectedSize, MessageDigest digest) {
        if (directory == null) throw new IllegalStateException("Replication spool is disabled");
        if (expectedSize < 0) throw new SpoolRejectedException(SpoolRejectedException.Reason.UNAVAILABLE,
                "Replication upload requires a known non-negative content size");
        if (expectedSize > policy.getMaxFileBytes()) {
            reject(SpoolRejectedException.Reason.TOO_LARGE,
                    "Upload exceeds the configured replication spool limit");
        }
        retryPendingCleanup();
        reserve(expectedSize);
        Path path = null;
        boolean success = false;
        try {
            checkDisk(expectedSize);
            path = Files.createTempFile(directory, "md-repl-", ".spool");
            long total = 0, nextCheck = policy.getCapacityCheckIntervalBytes();
            byte[] buffer = new byte[8192];
            try (OutputStream out = outputFactory.open(path)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("spooling interrupted");
                    if (total + read > expectedSize || total + read > policy.getMaxFileBytes()) {
                        reject(SpoolRejectedException.Reason.TOO_LARGE,
                                "Uploaded content exceeds its declared or configured size");
                    }
                    if (total + read >= nextCheck) {
                        checkDisk(read);
                        nextCheck = total + read + policy.getCapacityCheckIntervalBytes();
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            if (total != expectedSize) throw new com.minidrive.exception.StorageException("Uploaded object size does not match metadata");
            success = true;
            return new Spool(path, total, HexFormat.of().formatHex(digest.digest()));
        } catch (RuntimeException e) {
            if (!(e instanceof SpoolRejectedException)) metrics.counter("minidrive_replication_spool_io_failures_total", "kind", "stream").increment();
            throw e;
        } catch (IOException e) {
            metrics.counter("minidrive_replication_spool_io_failures_total", "kind", "filesystem").increment();
            throw new SpoolRejectedException(SpoolRejectedException.Reason.UNAVAILABLE,
                    "Replication spool storage is unavailable", e);
        } finally {
            if (!success) {
                finish(path, expectedSize);
            }
        }
    }

    private synchronized void reserve(long bytes) {
        if (active.get() >= policy.getMaxConcurrent()) reject(SpoolRejectedException.Reason.CONCURRENCY, "Replication upload capacity is busy; retry later");
        if (bytes > policy.getMaxReservedBytes() - reserved.get()) reject(SpoolRejectedException.Reason.CAPACITY, "Replication spool capacity is temporarily unavailable");
        active.incrementAndGet(); reserved.addAndGet(bytes);
    }

    private synchronized void releaseReservation(long bytes) { reserved.addAndGet(-bytes); }
    private synchronized void releaseActive() { active.decrementAndGet(); }

    private void finish(Path path, long bytes) {
        releaseActive();
        if (delete(path)) releaseReservation(bytes);
        else if (path != null) pendingCleanup.put(path, bytes);
    }

    private void checkDisk(long additionalBytes) {
        try {
            long usable = Files.getFileStore(directory).getUsableSpace();
            if (usable < policy.getMinFreeBytes() || additionalBytes > usable - policy.getMinFreeBytes()) {
                reject(SpoolRejectedException.Reason.CAPACITY, "Replication spool filesystem is below its configured free-space reserve");
            }
        } catch (IOException e) {
            throw new SpoolRejectedException(SpoolRejectedException.Reason.UNAVAILABLE, "Unable to check replication spool capacity", e);
        }
    }

    private void reject(SpoolRejectedException.Reason reason, String message) {
        metrics.counter("minidrive_replication_spool_rejections_total", "reason", reason.name().toLowerCase()).increment();
        throw new SpoolRejectedException(reason, message);
    }

    private void recoverAbandoned(Path directory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "md-repl-*.spool")) {
            for (Path path : files) {
                if (delete(path)) {
                    metrics.counter("minidrive_replication_spool_recovery_total", "outcome", "deleted").increment();
                } else {
                    long size = Files.size(path);
                    reserved.addAndGet(size);
                    pendingCleanup.put(path, size);
                }
            }
        }
    }

    private boolean delete(Path path) {
        if (path == null) return true;
        try { Files.deleteIfExists(path); return true; }
        catch (IOException e) {
            metrics.counter("minidrive_replication_spool_cleanup_failures_total").increment();
            log.error("Unable to remove replication spool file; cleanup will retry or restart recovery will retry", e);
            return false;
        }
    }

    private void retryPendingCleanup() {
        pendingCleanup.forEach((path, bytes) -> {
            if (delete(path) && pendingCleanup.remove(path, bytes)) releaseReservation(bytes);
        });
    }

    @Override
    public void close() {
        if (ownerLock != null) {
            try { ownerLock.release(); }
            catch (IOException e) { log.warn("Unable to release replication spool directory lock cleanly", e); }
            try { ownerChannel.close(); }
            catch (IOException e) { log.warn("Unable to close replication spool directory lock file cleanly", e); }
        }
    }

    public final class Spool implements AutoCloseable {
        private final Path path;
        private final long size;
        private final String checksum;
        private boolean closed;
        private Spool(Path path, long size, String checksum) { this.path = path; this.size = size; this.checksum = checksum; }
        public Path path() { return path; }
        public long size() { return size; }
        public String checksum() { return checksum; }
        @Override public synchronized void close() {
            if (!closed) { closed = true; finish(path, size); }
        }
    }
}
