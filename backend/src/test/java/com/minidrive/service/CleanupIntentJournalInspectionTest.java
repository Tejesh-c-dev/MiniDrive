package com.minidrive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CleanupIntentJournalInspectionTest {
    @TempDir Path temp;

    @Test void distinguishesEmptyJournalAndReportsMalformedEntriesWithoutRemovingEvidence() throws Exception {
        Path directory = temp.resolve("journal");
        CleanupIntentJournal journal = new CleanupIntentJournal(directory.toString());
        assertTrue(journal.inspect(10).intents().isEmpty());
        assertTrue(journal.inspect(10).issues().isEmpty());

        journal.record("test-object");
        Path malformed = directory.resolve("broken.intent");
        Files.writeString(malformed, "partial-write!");

        CleanupIntentJournal.Inspection inspection = journal.inspect(10);
        assertEquals(1, inspection.intents().size());
        assertEquals("test-object", inspection.intents().get(0).objectKey());
        assertEquals("broken.intent", inspection.issues().get(0).reference());
        assertTrue(Files.exists(malformed), "read-only inspection must retain evidence");
        assertFalse(inspection.limitReached());
    }

    @Test void reportsUnavailableJournalPathInsteadOfTreatingItAsEmpty() throws Exception {
        Path blocked = temp.resolve("not-a-directory");
        Files.writeString(blocked, "operator evidence");
        CleanupIntentJournal journal = new CleanupIntentJournal(blocked.toString());

        CleanupIntentJournal.Inspection inspection = journal.inspect(2);
        assertTrue(inspection.intents().isEmpty());
        assertEquals("not-a-directory", inspection.issues().get(0).reference());
        assertFalse(inspection.limitReached());
        assertEquals("operator evidence", Files.readString(blocked));
    }

    @Test void boundsJournalInventoryAndSignalsTruncation() throws Exception {
        CleanupIntentJournal journal = new CleanupIntentJournal(temp.resolve("bounded").toString());
        journal.record("one");
        journal.record("two");
        CleanupIntentJournal.Inspection inspection = journal.inspect(1);
        assertEquals(1, inspection.intents().size());
        assertTrue(inspection.limitReached());
    }
}
