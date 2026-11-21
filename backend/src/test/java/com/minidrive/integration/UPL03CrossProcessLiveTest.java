package com.minidrive.integration;

import com.minidrive.service.CleanupIntentJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the cleanup key lock is held across JVM/process boundaries. */
@EnabledIfSystemProperty(named = "minidrive.live-pg-minio", matches = "true")
class UPL03CrossProcessLiveTest {
    @Test
    void cleanupKeyLockBlocksIndependentJvmUntilOwnerReleasesIt(@TempDir Path temp) throws Exception {
        Path ready = temp.resolve("child-ready");
        Path release = temp.resolve("release");
        String key = "_uploads/upl03-cross-process/" + System.nanoTime();
        CleanupIntentJournal journal = new CleanupIntentJournal(temp.resolve("journal").toString());

        Process child;
        try (CleanupIntentJournal.LockHandle ignored = journal.acquire(key)) {
            child = startProbe(temp.resolve("journal"), key, ready, release);
            Thread.sleep(500);
            assertFalse(Files.exists(ready), "child JVM acquired the lock while the parent still held it");
            Files.writeString(release, "release");
        }

        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child JVM did not finish after lock release");
        assertEquals(0, child.exitValue(), "child JVM failed: " + new String(child.getErrorStream().readAllBytes()));
        assertTrue(Files.exists(ready), "child JVM never acquired the released lock");
    }

    private static Process startProbe(Path directory, String key, Path ready, Path release) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CleanupJournalLockProbe.class.getName(), directory.toString(), key,
                ready.toString(), release.toString()).redirectErrorStream(false).start();
    }
}
