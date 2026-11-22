package com.minidrive.integration;

import com.minidrive.service.CleanupIntentJournal;

import java.nio.file.Files;
import java.nio.file.Path;

/** Small forked JVM used by the live UPL-03 process-boundary test. */
public final class CleanupJournalLockProbe {
    private CleanupJournalLockProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("directory, key, ready, release required");
        Path ready = Path.of(args[2]);
        Path release = Path.of(args[3]);
        CleanupIntentJournal journal = new CleanupIntentJournal(args[0]);
        try (CleanupIntentJournal.LockHandle ignored = journal.acquire(args[1])) {
            Files.writeString(ready, "acquired");
            while (!Files.exists(release)) Thread.sleep(25);
        }
    }
}
