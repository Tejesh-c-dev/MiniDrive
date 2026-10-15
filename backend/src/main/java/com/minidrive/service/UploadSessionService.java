package com.minidrive.service;

import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.dto.upload.ChunkUploadResponse;
import com.minidrive.dto.upload.CreateUploadSessionRequest;
import com.minidrive.dto.upload.UploadSessionResponse;
import com.minidrive.dto.upload.UploadSessionStatusResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.Folder;
import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.exception.StorageException;
import com.minidrive.mapper.FileMapper;
import com.minidrive.mapper.UploadSessionMapper;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;
import com.minidrive.util.StorageKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.security.DigestInputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Backend foundation for resumable, chunked uploads (Phase 8.2).
 *
 * <p>A session is initialized from declared metadata; the server then fixes the
 * chunk size and derives the exact chunk count, and clients upload each chunk
 * independently. Finalization (Phase 8.4) assembles the staged chunks into a
 * real {@link File} and records the result through the same file/version
 * lifecycle used by the single-shot upload and replacement flows.</p>
 *
 * <p>All identity comes from the authenticated principal, and every lookup is
 * scoped by owner so one user can never see or mutate another's session.</p>
 */
@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    /** Explicit upper bound on a single logical upload. */
    static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024; // 5 GiB

    /** Server-selected chunk size; clients never choose how an object is split. */
    static final int CHUNK_SIZE_BYTES = 5 * 1024 * 1024; // 5 MiB

    /** Explicit upper bound on the derived chunk count. */
    static final int MAX_CHUNKS = 10_000;

    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /** Reserved staging namespace; never exposed to clients. */
    private static final String STAGING_PREFIX = "_uploads/";

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final int MAX_NAME_LENGTH = 255;
    private static final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("^[\\w.+-]+/[\\w.+-]+$");

    private final UploadSessionRepository uploadSessionRepository;
    private final UploadChunkRepository uploadChunkRepository;
    private final FolderRepository folderRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final FileAccessService fileAccessService;
    private final FileService fileService;

    /**
     * Programmatic transaction boundary for the completion phases. Completion
     * must interleave short database transactions with object-storage I/O that
     * cannot participate in them, and the metadata step has to run inside the
     * same transaction as the session's status change; a {@link TransactionTemplate}
     * expresses those discrete boundaries without relying on self-injection to
     * cross a proxy.
     */
    private final TransactionTemplate transactionTemplate;

    public UploadSessionService(
            UploadSessionRepository uploadSessionRepository,
            UploadChunkRepository uploadChunkRepository,
            FolderRepository folderRepository,
            FileRepository fileRepository,
            UserRepository userRepository,
            StorageService storageService,
            FileAccessService fileAccessService,
            FileService fileService,
            PlatformTransactionManager transactionManager) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.uploadChunkRepository = uploadChunkRepository;
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.fileAccessService = fileAccessService;
        this.fileService = fileService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Initializes a chunked upload from declared metadata. The declared size
     * must be within the file-size limit; the chunk size and chunk count are
     * computed server-side so clients cannot request an inconsistent split.
     */
    public UploadSessionResponse createSession(
            Authentication authentication,
            CreateUploadSessionRequest request) {

        return createSession(authentication, request, null);
    }

    public UploadSessionResponse createSession(
            Authentication authentication,
            CreateUploadSessionRequest request,
            String idempotencyKey) {

        User owner = currentUser(authentication);

        String filename = normalizeAndValidateName(request.getFilename());
        String contentType = normalizeAndValidateContentType(request.getContentType());
        long totalSize = validateDeclaredSize(request.getTotalSizeBytes());

        int chunkSize = CHUNK_SIZE_BYTES;
        int totalChunks = computeTotalChunks(totalSize, chunkSize);

        Folder folder = resolveOwnedFolder(owner, request.getFolderId());
        File targetFile = resolveTargetFile(owner, request.getTargetFileId());

        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (key != null && (key.isEmpty() || key.length() > 200)) {
            throw new IllegalArgumentException("Idempotency-Key must be between 1 and 200 characters");
        }
        String requestHash = requestHash(filename, contentType, totalSize,
                request.getFolderId(), request.getTargetFileId());
        if (key != null) {
            UploadSession existing = uploadSessionRepository
                    .findByOwnerIdAndCreateIdempotencyKey(owner.getId(), key).orElse(null);
            if (existing != null) return existingSession(existing, requestHash);
        }

        UploadSession session = new UploadSession(
                owner,
                folder,
                targetFile,
                filename,
                contentType,
                totalSize,
                chunkSize,
                totalChunks,
                UploadSessionStatus.INITIATED,
                LocalDateTime.now().plus(SESSION_TTL));
        if (key != null) session.setCreateIdempotency(key, requestHash);
        try {
            UploadSession saved = transactionTemplate.execute(status ->
                    uploadSessionRepository.saveAndFlush(session));
            return UploadSessionMapper.toResponse(saved);
        } catch (DataIntegrityViolationException race) {
            if (key == null) throw race;
            UploadSession winner = transactionTemplate.execute(status ->
                    uploadSessionRepository.findByOwnerIdAndCreateIdempotencyKey(owner.getId(), key)
                            .orElseThrow(() -> race));
            return existingSession(winner, requestHash);
        }
    }

    private UploadSessionResponse existingSession(UploadSession session, String requestHash) {
        if (!requestHash.equals(session.getCreateRequestHash())) {
            throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
        }
        return UploadSessionMapper.toResponse(session);
    }

    private String requestHash(String filename, String contentType, long size, UUID folderId, UUID targetId) {
        String canonical = filename + "\n" + contentType + "\n" + size + "\n" + folderId + "\n" + targetId;
        return sha256Hex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Returns the session status and the sorted indexes already persisted. */
    @Transactional(readOnly = true)
    public UploadSessionStatusResponse getStatus(
            Authentication authentication,
            UUID sessionId) {

        User owner = currentUser(authentication);
        UploadSession session = requireOwnedSession(owner, sessionId);

        List<Integer> uploaded = uploadChunkRepository
                .findBySessionIdOrderByChunkIndexAsc(session.getId())
                .stream()
                .map(UploadChunk::getChunkIndex)
                .toList();

        return UploadSessionMapper.toStatusResponse(session, uploaded);
    }

    /**
     * Ingests exactly one chunk.
     *
     * <p>The chunk is validated against the session's declared split before any
     * object is written: the index must be in range, the session must be
     * writable, and the body must contain exactly the expected number of bytes.
     * Bytes are then written to a deterministic staging key and the chunk row is
     * persisted only afterwards, so a row never references bytes that were not
     * stored. If persistence fails, the just-written object is removed.</p>
     *
     * <p>This method is intentionally not transactional: the object-storage
     * write and the database write cannot share a transaction, and keeping the
     * persistence in its own transaction lets a concurrent same-index insert be
     * caught and reconciled instead of poisoning an outer transaction.</p>
     */
    public ChunkUploadResponse ingestChunk(
            Authentication authentication,
            UUID sessionId,
            int chunkIndex,
            InputStream body) {

        User owner = currentUser(authentication);
        UploadSession session = requireOwnedSession(owner, sessionId);

        if (session.getStatus() != UploadSessionStatus.INITIATED) {
            throw new IllegalArgumentException(
                    "Upload session is not accepting chunks in status " + session.getStatus());
        }

        if (isExpired(session)) {
            throw new IllegalArgumentException("Upload session has expired");
        }

        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new IllegalArgumentException(
                    "Chunk index must be between 0 and " + (session.getTotalChunks() - 1));
        }

        long expectedSize = expectedChunkSize(session, chunkIndex);
        byte[] data = readExactly(body, expectedSize);
        String checksum = sha256Hex(data);

        String stagingKey = stagingObjectKey(owner.getId(), session.getId(), chunkIndex);

        // The object is written first; the row is only created once it exists.
        storageService.upload(
                stagingKey, new ByteArrayInputStream(data), data.length, OCTET_STREAM);

        return persistChunk(session, chunkIndex, data.length, checksum, stagingKey);
    }

    /**
     * Aborts an upload session and removes every staging object it produced.
     *
     * <p>Staging objects are removed before the rows are deleted: if storage
     * removal fails the transaction rolls back, leaving the session initiated
     * and retryable rather than half-aborted. Aborting an already-aborted
     * session is a no-op so the operation is idempotent.</p>
     */
    @Transactional
    public void abort(Authentication authentication, UUID sessionId) {

        User owner = currentUser(authentication);
        UploadSession session = requireOwnedSession(owner, sessionId);

        if (session.getStatus() == UploadSessionStatus.ABORTED) {
            return;
        }

        if (session.getStatus() != UploadSessionStatus.INITIATED) {
            throw new IllegalArgumentException(
                    "Only an initiated upload can be aborted, current status " + session.getStatus());
        }

        List<UploadChunk> chunks =
                uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(session.getId());

        for (UploadChunk chunk : chunks) {
            storageService.delete(chunk.getStagingObjectKey());
        }

        uploadChunkRepository.deleteBySessionId(session.getId());
        session.setStatus(UploadSessionStatus.ABORTED);
        uploadSessionRepository.saveAndFlush(session);
    }

    /**
     * Finalizes a fully staged session into a real {@link File} and records the
     * result as a {@code FileVersion} (Phase 8.4).
     *
     * <p>The operation is authenticated and owner-scoped: the session is looked
     * up by owner, so another user's session resolves to {@code 404}. Completion
     * is only allowed while a session is {@code INITIATED} and unexpired, and
     * only after every declared chunk is present exactly once at the expected
     * size. A zero-byte upload follows the Phase 8.2 contract of a single empty
     * chunk.</p>
     *
     * <p>PostgreSQL and object storage cannot share a transaction, so this method
     * coordinates them explicitly. Bytes are assembled in ascending index order
     * with bounded memory (at most one chunk stream is open at a time) and written
     * to a fresh final object key; only then is the file metadata persisted. The
     * metadata write and the session's transition to {@code COMPLETED} run in a
     * single row-locked transaction, so two simultaneous completion attempts
     * cannot produce two files or two versions. Staging objects are removed only
     * after that transaction commits, and cleanup failures can never roll back or
     * corrupt the completed file. A repeated completion of an already-completed
     * session returns the recorded file/version instead of creating a new one.</p>
     */
    public FileUploadResponse complete(Authentication authentication, UUID sessionId) {

        User caller = currentUser(authentication);
        UUID callerId = caller.getId();

        // Plan under a read transaction so the session's lazy associations
        // (folder, replacement target and its owner) are available, and so a
        // malformed or already-completed session is rejected before any bytes
        // are moved.
        CompletionPlan plan = transactionTemplate.execute(
                status -> buildCompletionPlan(callerId, sessionId));

        if (plan.alreadyCompleted()) {
            return plan.completedResponse();
        }

        String objectKey = plan.newObjectKey();

        // Assemble and store the final object before any metadata is written. If
        // this fails there is nothing durable to compensate: the session stays
        // INITIATED and the client may retry.
        MessageDigest wholeDigest = sha256Digest();
        try (InputStream assembled = new DigestInputStream(assemble(plan.chunks()), wholeDigest)) {
            storageService.upload(
                    objectKey, assembled, plan.totalSizeBytes(), plan.contentType());
        } catch (IOException closeFailure) {
            throw new StorageException("Unable to assemble uploaded chunks", closeFailure);
        } catch (RuntimeException assemblyFailure) {
            discardObject(objectKey, assemblyFailure);
            ChecksumMismatchException checksumMismatch = findChecksumMismatch(assemblyFailure);
            if (checksumMismatch != null) throw checksumMismatch;
            // A concurrent completion may have finalized the session and removed
            // the staging objects while this attempt was still reading them, so a
            // staging read can legitimately fail here. In that case this attempt
            // is redundant: return the already-recorded result instead of failing
            // a client whose upload genuinely completed.
            FileUploadResponse recorded = recordedResultIfCompleted(callerId, sessionId);
            if (recorded != null) {
                discardObject(objectKey, null);
                return recorded;
            }
            throw assemblyFailure;
        }

        CompletionOutcome outcome;
        try {
            String checksum = HexFormat.of().formatHex(wholeDigest.digest());
            outcome = transactionTemplate.execute(
                    status -> persistCompletion(plan, objectKey, callerId, checksum));
        } catch (RuntimeException databaseFailure) {
            // Metadata did not persist, so the just-written object is orphaned
            // and must be removed; the session is left INITIATED for a retry.
            discardObject(objectKey, databaseFailure);
            throw databaseFailure;
        }

        if (!outcome.objectConsumed()) {
            // Another completion won the race; our uploaded object is
            // unreferenced, so we remove it and return the winner's result.
            discardObject(objectKey, null);
        }

        removeStaging(plan.sessionId(), plan.chunks());
        return outcome.response();
    }

    /**
     * Resolves everything completion needs in one read-only pass: session
     * status/expiry, the full chunk set, and the final object key. A session
     * that already completed short-circuits to its recorded result so a repeat
     * request never touches storage again.
     */
    private CompletionPlan buildCompletionPlan(UUID ownerId, UUID sessionId) {

        UploadSession session = uploadSessionRepository.findByIdAndOwnerId(sessionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Upload session not found"));

        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return CompletionPlan.completed(recordedResponse(session));
        }

        if (session.getStatus() != UploadSessionStatus.INITIATED) {
            throw new IllegalArgumentException(
                    "Upload session cannot be completed in status " + session.getStatus());
        }

        if (isExpired(session)) {
            throw new IllegalArgumentException("Upload session has expired");
        }

        List<UploadChunk> chunks = requireCompleteChunkSet(session);

        boolean replacement = session.getTargetFile() != null;
        UUID folderId = session.getFolder() != null ? session.getFolder().getId() : null;
        UUID targetFileId = null;
        UUID keyOwnerId = ownerId;

        if (replacement) {
            // Re-check edit access now: the collaborator who started the session
            // may have lost permission since it was created.
            FileAccessService.FileAccess access = fileAccessService.resolve(
                    session.getOwner(), session.getTargetFile().getId());
            if (!access.hasAccess()) {
                throw new ForbiddenException("You do not have access to this file");
            }
            if (!access.canEdit()) {
                throw new ForbiddenException(
                        "You do not have permission to replace this file's content");
            }
            targetFileId = session.getTargetFile().getId();
            // Replacement objects stay under the file owner's namespace, matching
            // the multipart replacement flow.
            keyOwnerId = session.getTargetFile().getOwner().getId();
        }

        return new CompletionPlan(
                sessionId,
                ownerId,
                folderId,
                targetFileId,
                replacement,
                session.getFilename(),
                session.getContentType(),
                session.getTotalSizeBytes(),
                StorageKeys.contentObjectKey(keyOwnerId, session.getFilename()),
                chunks,
                null);
    }

    /**
     * Persists the completed file inside a row-locked transaction. The lock on
     * the session row is what serializes concurrent completions: the first to
     * acquire it writes the file/version and marks the session completed, and any
     * later attempt observes {@code COMPLETED} and returns the same result
     * without creating anything.
     */
    private CompletionOutcome persistCompletion(
            CompletionPlan plan, String objectKey, UUID callerId, String checksum) {

        UploadSession session = uploadSessionRepository
                .findByIdAndOwnerIdForUpdate(plan.sessionId(), plan.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("Upload session not found"));

        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return new CompletionOutcome(false, recordedResponse(session));
        }

        if (session.getStatus() != UploadSessionStatus.INITIATED) {
            throw new IllegalArgumentException(
                    "Upload session cannot be completed in status " + session.getStatus());
        }

        User uploader = userRepository.getReferenceById(callerId);

        FileUploadResponse response;
        if (plan.replacement()) {
            File target = fileRepository.findById(plan.targetFileId())
                    .orElseThrow(() -> new ResourceNotFoundException("File not found"));
            response = fileService.persistStoredReplacement(
                    target,
                    plan.filename(),
                    plan.contentType(),
                    plan.totalSizeBytes(),
                    objectKey,
                    uploader,
                    checksum);
        } else {
            User owner = userRepository.getReferenceById(plan.ownerId());
            Folder folder = plan.folderId() != null
                    ? folderRepository.getReferenceById(plan.folderId())
                    : null;
            response = fileService.persistStoredNewFile(
                    owner,
                    folder,
                    plan.filename(),
                    plan.contentType(),
                    plan.totalSizeBytes(),
                    objectKey,
                    uploader,
                    checksum);
        }

        session.markCompleted(response.getId(), response.getCurrentVersion());
        uploadSessionRepository.saveAndFlush(session);

        return new CompletionOutcome(true, response);
    }

    /**
     * Returns the recorded result if the session has completed since this attempt
     * started, or {@code null} when it is still in progress (or gone). Used to
     * make a redundant concurrent attempt succeed with the winner's result.
     */
    private FileUploadResponse recordedResultIfCompleted(UUID ownerId, UUID sessionId) {

        return transactionTemplate.execute(status -> {
            UploadSession session = uploadSessionRepository
                    .findByIdAndOwnerId(sessionId, ownerId)
                    .orElse(null);
            if (session == null || session.getStatus() != UploadSessionStatus.COMPLETED) {
                return null;
            }
            return recordedResponse(session);
        });
    }

    /** Rebuilds the recorded result of a completed session for an idempotent repeat. */
    private FileUploadResponse recordedResponse(UploadSession session) {

        UUID fileId = session.getCompletedFileId();
        if (fileId == null) {
            // The completed file was deleted; the session tombstone remains but
            // there is no result to return.
            throw new ResourceNotFoundException(
                    "The file produced by this upload no longer exists");
        }

        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The file produced by this upload no longer exists"));

        int version = session.getCompletedVersionNumber() != null
                ? session.getCompletedVersionNumber()
                : 0;
        return FileMapper.toUploadResponse(file, "UPLOADED", version);
    }

    /**
     * Confirms every declared chunk is present exactly once at its expected size
     * and that the sizes sum to the declared total. Indexes ascending without
     * gaps also rules out duplicates, and the sum check catches any drift even if
     * a row were inserted out of band.
     */
    private List<UploadChunk> requireCompleteChunkSet(UploadSession session) {

        List<UploadChunk> chunks = uploadChunkRepository
                .findBySessionIdOrderByChunkIndexAsc(session.getId());

        if (chunks.size() != session.getTotalChunks()) {
            throw new IllegalArgumentException("Upload is incomplete: expected "
                    + session.getTotalChunks() + " chunks but found " + chunks.size());
        }

        long total = 0;
        for (int index = 0; index < chunks.size(); index++) {
            UploadChunk chunk = chunks.get(index);
            if (chunk.getChunkIndex() != index) {
                throw new IllegalArgumentException(
                        "Upload has a gap or duplicate at index " + index);
            }
            long expected = expectedChunkSize(session, index);
            if (chunk.getSizeBytes() != expected) {
                throw new IllegalArgumentException("Chunk " + index + " has "
                        + chunk.getSizeBytes() + " bytes but expected " + expected);
            }
            total += chunk.getSizeBytes();
        }

        if (total != session.getTotalSizeBytes()) {
            throw new IllegalArgumentException("Uploaded bytes " + total
                    + " do not match the declared size of "
                    + session.getTotalSizeBytes());
        }

        return chunks;
    }

    /**
     * Concatenates the staged chunk objects in ascending index order without ever
     * buffering the assembled file. The backing objects are opened lazily as the
     * previous one is exhausted and closed as it is consumed, so at most one
     * chunk stream is held at a time no matter how many chunks there are.
     */
    private InputStream assemble(List<UploadChunk> chunks) {

        Enumeration<InputStream> parts = new Enumeration<>() {
            private int next = 0;

            @Override
            public boolean hasMoreElements() {
                return next < chunks.size();
            }

            @Override
            public InputStream nextElement() {
                UploadChunk chunk = chunks.get(next++);
                MessageDigest digest = sha256Digest();
                return new FilterInputStream(storageService.download(chunk.getStagingObjectKey())) {
                    private boolean verified;

                    private void verify() throws IOException {
                        if (verified) return;
                        verified = true;
                        String actual = HexFormat.of().formatHex(digest.digest());
                        if (chunk.getChecksum() != null && !chunk.getChecksum().equals(actual)) {
                            throw new ChecksumMismatchException(
                                    "Uploaded content checksum mismatch for chunk "
                                            + chunk.getChunkIndex());
                        }
                    }

                    @Override public int read() throws IOException {
                        int value = super.read();
                        if (value < 0) verify(); else digest.update((byte) value);
                        return value;
                    }

                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count = super.read(bytes, offset, length);
                        if (count < 0) verify(); else digest.update(bytes, offset, count);
                        return count;
                    }
                };
            }
        };

        return new SequenceInputStream(parts);
    }

    /** Removes a just-written final object, preserving a cleanup failure as suppressed. */
    private void discardObject(String objectKey, RuntimeException cause) {

        try {
            storageService.delete(objectKey);
        } catch (RuntimeException cleanupFailure) {
            log.error("Failed to clean up final object {} after completion failure",
                    objectKey, cleanupFailure);
            if (cause != null) {
                cause.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Removes the session's staging objects and chunk rows after the file has
     * been durably persisted. This is deliberately best-effort: the completed
     * file is already valid, so a cleanup failure is logged and swallowed rather
     * than turning a successful completion into an error.
     */
    private void removeStaging(UUID sessionId, List<UploadChunk> chunks) {

        for (UploadChunk chunk : chunks) {
            try {
                storageService.delete(chunk.getStagingObjectKey());
            } catch (RuntimeException cleanupFailure) {
                log.warn("Failed to remove staging object {} after successful completion;"
                        + " the completed file is unaffected",
                        chunk.getStagingObjectKey(), cleanupFailure);
            }
        }

        try {
            transactionTemplate.executeWithoutResult(
                    status -> uploadChunkRepository.deleteBySessionId(sessionId));
        } catch (RuntimeException cleanupFailure) {
            log.warn("Failed to remove chunk rows for completed session {};"
                    + " the completed file is unaffected", sessionId, cleanupFailure);
        }
    }

    /**
     * Everything completion needs to assemble and persist a finalized file,
     * resolved once so the storage phase needs no database session. A plan whose
     * {@code completedResponse} is set represents an already-completed session.
     */
    private record CompletionPlan(
            UUID sessionId,
            UUID ownerId,
            UUID folderId,
            UUID targetFileId,
            boolean replacement,
            String filename,
            String contentType,
            long totalSizeBytes,
            String newObjectKey,
            List<UploadChunk> chunks,
            FileUploadResponse completedResponse) {

        boolean alreadyCompleted() {
            return completedResponse != null;
        }

        static CompletionPlan completed(FileUploadResponse response) {
            return new CompletionPlan(
                    null, null, null, null, false, null, null, 0, null, List.of(), response);
        }
    }

    /** Outcome of the locked persistence step. */
    private record CompletionOutcome(boolean objectConsumed, FileUploadResponse response) {
    }

    private ChecksumMismatchException findChecksumMismatch(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ChecksumMismatchException mismatch) return mismatch;
            if (cause.getCause() == cause) break;
        }
        return null;
    }

    private static final class ChecksumMismatchException extends IllegalArgumentException {
        private ChecksumMismatchException(String message) { super(message); }
    }

    /**
     * Persists one chunk row, treating a same-index retry as an idempotent
     * success. A concurrent insert is detected through the unique
     * {@code (session_id, chunk_index)} constraint; in that case the row that
     * won the race is kept and no cleanup is performed because the staging key
     * is shared and deterministic. Any other persistence failure removes the
     * freshly written object before rethrowing.
     */
    private ChunkUploadResponse persistChunk(
            UploadSession session,
            int chunkIndex,
            long sizeBytes,
            String checksum,
            String stagingKey) {

        if (uploadChunkRepository
                .findBySessionIdAndChunkIndex(session.getId(), chunkIndex)
                .isPresent()) {
            return new ChunkUploadResponse(session.getId(), chunkIndex, sizeBytes, true);
        }

        try {
            uploadChunkRepository.saveAndFlush(
                    new UploadChunk(session, chunkIndex, sizeBytes, checksum, stagingKey));
            return new ChunkUploadResponse(session.getId(), chunkIndex, sizeBytes, false);
        } catch (DataIntegrityViolationException constraintFailure) {
            // The unique (session_id, chunk_index) constraint is the arbiter of
            // a concurrent same-index race: if a row now exists, another writer
            // won and shares our deterministic staging key, so this is a
            // successful duplicate rather than a failure.
            if (uploadChunkRepository
                    .findBySessionIdAndChunkIndex(session.getId(), chunkIndex)
                    .isPresent()) {
                return new ChunkUploadResponse(session.getId(), chunkIndex, sizeBytes, true);
            }
            removeStagingObject(stagingKey, constraintFailure);
            throw constraintFailure;
        } catch (RuntimeException databaseFailure) {
            removeStagingObject(stagingKey, databaseFailure);
            throw databaseFailure;
        }
    }

    /** Removes a just-written staging object, preserving a cleanup failure as suppressed. */
    private void removeStagingObject(String stagingKey, RuntimeException cause) {

        try {
            storageService.delete(stagingKey);
        } catch (RuntimeException cleanupFailure) {
            log.error("Failed to clean up staging object {} after chunk persistence failure",
                    stagingKey, cleanupFailure);
            cause.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Reads the chunk body, rejecting both short and oversized payloads. At most
     * the expected size is buffered, so a malicious over-long body cannot be
     * buffered without bound.
     */
    private byte[] readExactly(InputStream body, long expectedSize) {

        if (body == null) {
            throw new IllegalArgumentException("Chunk body is required");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(expectedSize, 64 * 1024));
        byte[] buffer = new byte[8192];
        long total = 0;

        try {
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize) {
                    throw new IllegalArgumentException(
                            "Chunk size exceeds the expected size of " + expectedSize + " bytes");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new StorageException("Unable to read uploaded chunk", e);
        }

        if (total != expectedSize) {
            throw new IllegalArgumentException(
                    "Chunk size " + total + " does not match the expected size of "
                            + expectedSize + " bytes");
        }

        return out.toByteArray();
    }

    private String sha256Hex(byte[] data) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Exact byte count expected for one index. Every non-final chunk is a full
     * chunk; the final chunk absorbs the remainder. A zero-byte upload has a
     * single chunk whose expected size is zero.
     */
    private long expectedChunkSize(UploadSession session, int chunkIndex) {

        if (chunkIndex == session.getTotalChunks() - 1) {
            return session.getTotalSizeBytes() - (long) chunkIndex * session.getChunkSizeBytes();
        }
        return session.getChunkSizeBytes();
    }

    private int computeTotalChunks(long totalSize, int chunkSize) {

        // A zero-byte file still needs exactly one (empty) chunk so the
        // last-chunk size formula and index bounds stay well defined.
        if (totalSize == 0) {
            return 1;
        }
        long chunks = (totalSize + chunkSize - 1) / chunkSize;
        if (chunks > MAX_CHUNKS) {
            throw new IllegalArgumentException(
                    "File requires more than " + MAX_CHUNKS + " chunks");
        }
        return (int) chunks;
    }

    private String stagingObjectKey(UUID ownerId, UUID sessionId, int chunkIndex) {
        return STAGING_PREFIX + ownerId + "/" + sessionId + "/" + chunkIndex;
    }

    private boolean isExpired(UploadSession session) {
        return session.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private UploadSession requireOwnedSession(User owner, UUID sessionId) {

        return uploadSessionRepository.findByIdAndOwnerId(sessionId, owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Upload session not found"));
    }

    private User currentUser(Authentication authentication) {

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Folder resolveOwnedFolder(User owner, UUID folderId) {

        if (folderId == null) {
            return null;
        }

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder not found"));

        if (!folder.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException("You do not have access to this folder");
        }

        return folder;
    }

    /**
     * Resolves the optional replacement target. The caller must be able to edit
     * the file (owner or editor), matching the authorization used when content
     * is replaced; the session itself remains owned by the caller.
     */
    private File resolveTargetFile(User caller, UUID targetFileId) {

        if (targetFileId == null) {
            return null;
        }

        FileAccessService.FileAccess access = fileAccessService.resolve(caller, targetFileId);

        if (!access.hasAccess()) {
            throw new ForbiddenException("You do not have access to this file");
        }

        if (!access.canEdit()) {
            throw new ForbiddenException(
                    "You do not have permission to replace this file's content");
        }

        return access.file();
    }

    private String normalizeAndValidateName(String name) {

        String normalized = name == null ? "" : name.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("File name must not be empty");
        }

        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "File name must be at most " + MAX_NAME_LENGTH + " characters");
        }

        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("File name must not contain path separators");
        }

        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("File name is invalid");
        }

        return normalized;
    }

    private String normalizeAndValidateContentType(String contentType) {

        String normalized = contentType == null ? "" : contentType.trim();
        String base = normalized.split(";", 2)[0].trim();

        if (!CONTENT_TYPE_PATTERN.matcher(base).matches()) {
            throw new IllegalArgumentException("Content type is invalid");
        }

        return base;
    }

    private long validateDeclaredSize(Long totalSizeBytes) {

        if (totalSizeBytes == null || totalSizeBytes < 0) {
            throw new IllegalArgumentException(
                    "Size in bytes must be a non-negative number");
        }

        if (totalSizeBytes > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File size must not exceed " + MAX_FILE_SIZE_BYTES + " bytes");
        }

        return totalSizeBytes;
    }
}
