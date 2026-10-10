package com.minidrive.service;

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
import com.minidrive.mapper.UploadSessionMapper;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Backend foundation for resumable, chunked uploads (Phase 8.2).
 *
 * <p>A session is initialized from declared metadata; the server then fixes the
 * chunk size and derives the exact chunk count, and clients upload each chunk
 * independently. This service deliberately stops short of assembling bytes into
 * a {@link File} or {@code FileVersion} -- that finalization is Phase 8.4 -- so
 * it never touches file metadata or version history.</p>
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

    public UploadSessionService(
            UploadSessionRepository uploadSessionRepository,
            UploadChunkRepository uploadChunkRepository,
            FolderRepository folderRepository,
            FileRepository fileRepository,
            UserRepository userRepository,
            StorageService storageService,
            FileAccessService fileAccessService) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.uploadChunkRepository = uploadChunkRepository;
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.fileAccessService = fileAccessService;
    }

    /**
     * Initializes a chunked upload from declared metadata. The declared size
     * must be within the file-size limit; the chunk size and chunk count are
     * computed server-side so clients cannot request an inconsistent split.
     */
    @Transactional
    public UploadSessionResponse createSession(
            Authentication authentication,
            CreateUploadSessionRequest request) {

        User owner = currentUser(authentication);

        String filename = normalizeAndValidateName(request.getFilename());
        String contentType = normalizeAndValidateContentType(request.getContentType());
        long totalSize = validateDeclaredSize(request.getTotalSizeBytes());

        int chunkSize = CHUNK_SIZE_BYTES;
        int totalChunks = computeTotalChunks(totalSize, chunkSize);

        Folder folder = resolveOwnedFolder(owner, request.getFolderId());
        File targetFile = resolveTargetFile(owner, request.getTargetFileId());

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

        return UploadSessionMapper.toResponse(
                uploadSessionRepository.saveAndFlush(session));
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
