package com.minidrive.storage;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit operational entry point; disabled unless requested for this process. */
@Component
public class ReplicaIntegrityBackfillRunner implements ApplicationRunner {
    private final boolean requested;
    private final boolean replicationEnabled;
    private final ReplicaIntegrityBackfill backfill;

    public ReplicaIntegrityBackfillRunner(
            @Value("${minidrive.storage.replication.backfill:false}") boolean requested,
            @Value("${minidrive.storage.replication.enabled:false}") boolean replicationEnabled,
            ReplicaIntegrityBackfill backfill) {
        this.requested = requested;
        this.replicationEnabled = replicationEnabled;
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!requested) return;
        if (replicationEnabled) throw new IllegalStateException(
                "Run replica integrity backfill with replication disabled; it must not use replication reads.");
        ReplicaIntegrityBackfill.Report report = backfill.run();
        if (report.failed() > 0 || report.missingLocation() > 0 || report.skippedDeleting() > 0)
            throw new IllegalStateException("Replica integrity backfill is incomplete: " + report);
    }
}
