package com.minidrive.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Independent durable intents for object deletes that PostgreSQL could not record. */
@Component
public class CleanupIntentJournal {
    private static final Logger log = LoggerFactory.getLogger(CleanupIntentJournal.class);
    // A fixed set of lock slots keeps metadata bounded. Hash collisions only serialize unrelated keys.
    private static final int LOCK_SLOTS = 65_536;
    private static final ConcurrentHashMap<Integer, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final AtomicBoolean directorySyncWarningLogged = new AtomicBoolean();
    private final Path directory;

    public CleanupIntentJournal(@Value("${minidrive.cleanup-journal.directory:./data/cleanup-journal}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    /**
     * Serializes a content-key writer with journal replay across threads and
     * processes. In upload transactions the lock remains held through commit
     * or rollback, including the commit outcome.
     */
    public void guardUntilTransactionCompletes(String objectKey) {
        LockHandle handle = acquire(objectKey);
        try {
            // Write ahead of Silo so a process crash after object creation cannot
            // lose the only recovery pointer while PostgreSQL is unavailable.
            record(objectKey);
            if (!TransactionSynchronizationManager.isSynchronizationActive())
                throw new IllegalStateException("Object-key coordination requires an active transaction");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    try {
                        if (status == STATUS_COMMITTED) resolve(objectKey);
                    } catch (RuntimeException journalFailure) {
                        // Keep the durable intent; replay will observe the committed reference and preserve it.
                        log.error("Unable to resolve committed upload intent for {}; replay will verify references",
                                objectKey, journalFailure);
                    } finally { handle.close(); }
                }
            });
        } catch (RuntimeException failure) {
            handle.close();
            throw failure;
        }
    }

    public LockHandle acquire(String objectKey) {
        int slot = lockSlot(objectKey);
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(slot, ignored -> new ReentrantLock());
        local.lock();
        try {
            Files.createDirectories(directory);
            FileChannel channel = FileChannel.open(directory.resolve("object-key-locks"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock(slot, 1, false);
            return new LockHandle(local, channel, fileLock);
        } catch (IOException | RuntimeException failure) {
            local.unlock();
            throw new IllegalStateException("Unable to coordinate object-key cleanup", failure);
        }
    }

    /** Persists an intent independently of PostgreSQL and forces its contents and directory entry. */
    public void record(String objectKey) {
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(lockSlot(objectKey), ignored -> new ReentrantLock());
        if (local.isHeldByCurrentThread()) {
            writeIntent(objectKey);
            return;
        }
        try (LockHandle ignored = acquire(objectKey)) {
            writeIntent(objectKey);
        }
    }

    private void writeIntent(String objectKey) {
        try {
            Files.createDirectories(directory);
            Path entry = entryPath(objectKey);
            byte[] bytes = Base64.getUrlEncoder().withoutPadding()
                    .encode(objectKey.getBytes(StandardCharsets.UTF_8));
            try (FileChannel channel = FileChannel.open(entry, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            forceDirectory();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to persist cleanup intent outside PostgreSQL", failure);
        }
    }

    public List<Intent> pending() {
        return inspect(500).intents();
    }

    /** A bounded, read-only inventory that preserves failures for reconciliation. */
    public Inspection inspect(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        try {
            if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory())
                return new Inspection(List.of(), List.of(
                        new Issue(directoryName(), "cleanup journal path is not a directory")), false);
        } catch (NoSuchFileException missing) {
            return new Inspection(List.of(), List.of(), false);
        } catch (IOException unavailable) {
            return new Inspection(List.of(), List.of(new Issue(directoryName(),
                    "unable to inspect cleanup journal directory (" + unavailable.getClass().getSimpleName() + ")")), false);
        }
        List<Intent> result = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        boolean limitReached = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.intent")) {
            for (Path entry : entries) {
                if (result.size() + issues.size() >= boundedLimit) { limitReached = true; break; }
                try {
                    String key = new String(Base64.getUrlDecoder().decode(Files.readAllBytes(entry)), StandardCharsets.UTF_8);
                    if (!key.isBlank() && entry.equals(entryPath(key))) result.add(new Intent(key, entry));
                    else issues.add(new Issue(entry.getFileName().toString(), "malformed cleanup intent retained"));
                } catch (RuntimeException | IOException corrupt) {
                    issues.add(new Issue(entry.getFileName().toString(), "unreadable cleanup intent retained ("
                            + corrupt.getClass().getSimpleName() + ")"));
                }
            }
        } catch (IOException failure) {
            issues.add(new Issue(directoryName(), "unable to enumerate cleanup journal ("
                    + failure.getClass().getSimpleName() + ")"));
        }
        return new Inspection(List.copyOf(result), List.copyOf(issues), limitReached);
    }

    public boolean isPending(Intent intent) { return Files.isRegularFile(intent.path()); }
    public String directoryName() { return directory.getFileName() == null ? "cleanup-journal" : directory.getFileName().toString(); }

    public void resolve(Intent intent) {
        resolvePath(intent.path());
    }

    public void resolve(String objectKey) { resolvePath(entryPath(objectKey)); }

    private void resolvePath(Path path) {
        try {
            Files.deleteIfExists(path);
            forceDirectory();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to resolve cleanup journal entry", failure);
        }
    }

    private Path entryPath(String key) { return directory.resolve(id(key) + ".intent"); }
    private static String id(String key) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int lockSlot(String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            int value = ByteBuffer.wrap(digest).getInt() & Integer.MAX_VALUE;
            return value % LOCK_SLOTS;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void forceDirectory() throws IOException {
        // Directory fsync is required for rename/create durability on supported deployment filesystems.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException unsupportedOnWindows) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) throw unsupportedOnWindows;
            // The intent file itself is still forced. Windows' standard JDK provider
            // does not expose directory handles for FlushFileBuffers; durable-volume
            // behavior is therefore a deployment/filesystem assumption on Windows.
            if (directorySyncWarningLogged.compareAndSet(false, true))
                log.warn("Directory fsync is unavailable through the Windows JDK provider for cleanup journal {}", directory);
        }
    }

    public record Intent(String objectKey, Path path) { }
    public record Issue(String reference, String detail) { }
    public record Inspection(List<Intent> intents, List<Issue> issues, boolean limitReached) { }

    public static final class LockHandle implements AutoCloseable {
        private final ReentrantLock local;
        private final FileChannel channel;
        private final FileLock fileLock;
        private boolean closed;
        private LockHandle(ReentrantLock local, FileChannel channel, FileLock fileLock) {
            this.local = local; this.channel = channel; this.fileLock = fileLock;
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { fileLock.release(); } catch (IOException e) { log.error("Unable to release cleanup lock", e); }
            try { channel.close(); } catch (IOException e) { log.error("Unable to close cleanup lock", e); }
            local.unlock();
        }
    }
}
