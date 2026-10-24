package com.minidrive.service;

import com.minidrive.entity.*;
import com.minidrive.exception.FolderDeletionInProgressException;
import com.minidrive.repository.*;
import com.minidrive.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** Durable outbox and idempotent worker for recursive folder object cleanup. */
@Service
public class FolderDeletionLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FolderDeletionLifecycle.class);
    private final FolderRepository folders;
    private final FileRepository files;
    private final FileVersionRepository versions;
    private final UploadSessionRepository sessions;
    private final UploadChunkRepository chunks;
    private final UserRepository users;
    private final FolderDeletionJobRepository jobs;
    private final FolderDeletionObjectRepository objects;
    private final StorageService storage;
    private final FileListingCache cache;
    private final TransactionTemplate tx;
    private final PlatformTransactionManager transactionManager;
    private final CleanupIntentJournal cleanupJournal;

    public FolderDeletionLifecycle(FolderRepository folders, FileRepository files,
            FileVersionRepository versions, UploadSessionRepository sessions,
            UploadChunkRepository chunks, UserRepository users, FolderDeletionJobRepository jobs,
            FolderDeletionObjectRepository objects, StorageService storage,
            FileListingCache cache, PlatformTransactionManager manager, CleanupIntentJournal cleanupJournal) {
        this.folders = folders; this.files = files; this.versions = versions; this.sessions = sessions;
        this.chunks = chunks; this.users = users; this.jobs = jobs; this.objects = objects;
        this.storage = storage; this.cache = cache; this.tx = new TransactionTemplate(manager);
        this.transactionManager = manager; this.cleanupJournal = cleanupJournal;
    }

    /** Establishes and commits the complete manifest before any storage call. */
    @Transactional
    public UUID request(UUID rootId, UUID ownerId) {
        users.findByIdForUpdate(ownerId).orElseThrow();
        Folder current = folders.findById(rootId).orElse(null);
        // Another authorized request may have completed while this request was
        // waiting for the owner lock. Treat that duplicate as already complete.
        if (current == null) return null;
        Optional<FolderDeletionJob> existing = jobs.findByRootFolderId(current.getId());
        if (existing.isPresent()) return existing.get().getId();

        Set<UUID> subtree = new HashSet<>();
        subtree.add(current.getId());
        List<Folder> ownerFolders = folders.findAll().stream()
                .filter(f -> f.getOwner().getId().equals(ownerId)).toList();
        boolean changed;
        do {
            changed = false;
            for (Folder f : ownerFolders) {
                if (f.getParentFolder() != null && subtree.contains(f.getParentFolder().getId()))
                    changed |= subtree.add(f.getId());
            }
        } while (changed);

        List<File> subtreeFiles = files.findByOwnerId(ownerId).stream()
                .filter(f -> f.getFolder() != null && subtree.contains(f.getFolder().getId())).toList();
        Set<String> manifest = new LinkedHashSet<>();
        Set<UUID> fileIds = new HashSet<>();
        for (File file : subtreeFiles) {
            fileIds.add(file.getId());
            if (file.getObjectKey() != null) manifest.add(file.getObjectKey());
        }
        for (File file : subtreeFiles)
            for (FileVersion version : versions.findByFileIdOrderByVersionNumberAsc(file.getId()))
                manifest.add(version.getObjectKey());

        FolderDeletionJob job = jobs.saveAndFlush(new FolderDeletionJob(UUID.randomUUID(), current.getId(), ownerId));
        for (UploadSession session : sessions.findAll()) {
            boolean associated = (session.getFolder() != null && subtree.contains(session.getFolder().getId()))
                    || (session.getTargetFile() != null && fileIds.contains(session.getTargetFile().getId()));
            if (associated)
                for (UploadChunk chunk : chunks.findBySessionIdOrderByChunkIndexAsc(session.getId()))
                    manifest.add(chunk.getStagingObjectKey());
        }
        for (String key : manifest) objects.save(new FolderDeletionObject(job, key));
        objects.flush();
        current.markDeleting();
        folders.saveAndFlush(current);
        return job.getId();
    }

    /**
     * Removes one file's metadata only after its current, historical, and
     * associated staging keys have been committed to the durable cleanup
     * manifest. Version objects remain retained while their version rows live;
     * explicit permanent file deletion retires the complete history.
     */
    @Transactional
    public UUID requestFile(UUID fileId, UUID ownerId) {
        users.findByIdForUpdate(ownerId).orElseThrow();
        File file = files.findByIdForUpdate(fileId).orElse(null);
        if (file == null) return null;
        if (!file.getOwner().getId().equals(ownerId))
            throw new IllegalArgumentException("File owner changed during deletion");
        if (file.getFolder() != null && folders.hasDeletingAncestor(file.getFolder().getId()))
            throw new FolderDeletionInProgressException();

        FolderDeletionJob job = jobs.saveAndFlush(new FolderDeletionJob(UUID.randomUUID()));
        Set<String> manifest = new LinkedHashSet<>();
        if (file.getObjectKey() != null) manifest.add(file.getObjectKey());
        for (FileVersion version : versions.findByFileIdOrderByVersionNumberAsc(fileId))
            if (version.getObjectKey() != null) manifest.add(version.getObjectKey());
        // The file FK cascades to replacement upload sessions. Preserve their
        // staging keys before deleting the file so cleanup cannot lose them.
        for (UploadSession session : sessions.findAll()) {
            if (session.getTargetFile() != null && session.getTargetFile().getId().equals(fileId))
                for (UploadChunk chunk : chunks.findBySessionIdOrderByChunkIndexAsc(session.getId()))
                    if (chunk.getStagingObjectKey() != null) manifest.add(chunk.getStagingObjectKey());
        }
        for (String key : manifest) objects.save(new FolderDeletionObject(job, key));
        objects.flush();
        files.delete(file);
        files.flush();
        cache.evictAfterCommit();
        return job.getId();
    }

    /** Durable fallback when an upload's compensating delete also fails. */
    public void scheduleObjectCleanup(String objectKey) {
        try {
            TransactionTemplate isolated = new TransactionTemplate(transactionManager);
            isolated.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            isolated.executeWithoutResult(status -> {
                FolderDeletionJob job = jobs.saveAndFlush(new FolderDeletionJob(UUID.randomUUID()));
                objects.saveAndFlush(new FolderDeletionObject(job, objectKey));
            });
        } catch (RuntimeException databaseFailure) {
            try {
                cleanupJournal.record(objectKey);
                log.error("PostgreSQL could not record cleanup for {}; independent durable intent recorded",
                        objectKey, databaseFailure);
            } catch (RuntimeException journalFailure) {
                databaseFailure.addSuppressed(journalFailure);
                log.error("PostgreSQL and cleanup journal both failed for {}; operator reconciliation is required",
                        objectKey, databaseFailure);
                throw databaseFailure;
            }
        }
    }

    /** One bounded synchronous pass; incomplete work stays pending for the scheduler. */
    public boolean attempt(UUID jobId) {
        try {
            tx.executeWithoutResult(s -> jobs.findByIdForUpdate(jobId).ifPresent(job -> {
                job.started();
                jobs.saveAndFlush(job);
            }));
            List<FolderDeletionObject> pending = tx.execute(s -> objects.findTop100ByJobIdAndDeletedFalseOrderById(jobId));
            for (FolderDeletionObject item : pending) {
                storage.delete(item.getObjectKey());
                tx.executeWithoutResult(s -> {
                    FolderDeletionObject current = objects.findById(item.getId()).orElse(null);
                    if (current != null) current.markDeleted();
                });
            }
            return finalizeIfReady(jobId);
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
                job.failed(failure);
                jobs.saveAndFlush(job);
            }));
            log.error("Folder deletion job {} remains pending after cleanup failure", jobId, failure);
            return false;
        }
    }

    public boolean attemptIfDue(UUID jobId) {
        boolean due = Boolean.TRUE.equals(tx.execute(s -> jobs.findById(jobId)
                .map(job -> !job.getNextAttemptAt().isAfter(LocalDateTime.now())).orElse(false)));
        return due ? attempt(jobId) : false;
    }

    private boolean finalizeIfReady(UUID jobId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            FolderDeletionJob job = jobs.findByIdForUpdate(jobId).orElse(null);
            if (job == null) return true;
            if (objects.existsByJobIdAndDeletedFalse(jobId)) return false;
            Folder root = job.getRootFolderId() == null
                    ? null : folders.findById(job.getRootFolderId()).orElse(null);
            // Folder rows cascade to files, versions, upload sessions and chunks.
            // Job FK restricts deletion, so remove the manifest only after every object succeeded.
            objects.deleteAll(objects.findByJobId(jobId));
            objects.flush();
            jobs.delete(job);
            jobs.flush();
            if (root != null) folders.delete(root);
            jobs.flush();
            if (root != null) cache.evictAfterCommit();
            return true;
        }));
    }

    @Scheduled(fixedDelayString = "${minidrive.folder-deletion.retry-delay-ms:30000}")
    public void retryPending() {
        List<FolderDeletionJob> due = jobs.findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                List.of("PENDING", "RETRY"), LocalDateTime.now());
        for (FolderDeletionJob job : due) attempt(job.getId());
    }

    /** Replays only explicit failed-delete intents, coordinating with every writer of the same key. */
    @Scheduled(fixedDelayString = "${minidrive.cleanup-journal.retry-delay-ms:30000}",
            initialDelayString = "${minidrive.cleanup-journal.initial-delay-ms:30000}")
    public void replayCleanupJournal() {
        for (CleanupIntentJournal.Intent intent : cleanupJournal.pending()) {
            try (CleanupIntentJournal.LockHandle ignored = cleanupJournal.acquire(intent.objectKey())) {
                // Another worker or the upload's commit callback may have resolved
                // this snapshot while we waited for the cross-process key lock.
                if (!cleanupJournal.isPending(intent)) continue;
                boolean referenced = Boolean.TRUE.equals(tx.execute(status -> files.existsByObjectKey(intent.objectKey())
                        || versions.existsByObjectKey(intent.objectKey())
                        || chunks.existsByStagingObjectKey(intent.objectKey())
                        || objects.existsByObjectKey(intent.objectKey())));
                if (referenced) {
                    cleanupJournal.resolve(intent);
                    log.warn("Preserved cleanup-intent object {} because a database reference remains",
                            intent.objectKey());
                    continue;
                }
                storage.delete(intent.objectKey());
                cleanupJournal.resolve(intent);
                log.info("Replayed independent cleanup intent for object {}", intent.objectKey());
            } catch (RuntimeException failure) {
                // Database outage, storage outage, or journal failure leaves the entry intact for retry.
                log.warn("Cleanup intent for object {} remains pending", intent.objectKey(), failure);
            }
        }
    }
}
