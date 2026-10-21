package com.minidrive.service;

import com.minidrive.dto.file.FileMetadataRequest;
import com.minidrive.dto.file.FileMetadataResponse;
import com.minidrive.dto.file.FileMetadataUpdateRequest;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.dto.file.FileVersionResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.FileVersion;
import com.minidrive.entity.Folder;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.mapper.FileMapper;
import com.minidrive.mapper.FileVersionMapper;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;
import com.minidrive.exception.StorageException;
import com.minidrive.util.StorageKeys;
import com.minidrive.metrics.MeasuredOperation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private static final int MAX_NAME_LENGTH = 255;
    private static final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("^[\\w.+-]+/[\\w.+-]+$");

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final FileAccessService fileAccessService;

    /**
     * Read-through cache for the owner's file listings (Phase 10.3). It holds no
     * authorization state: this service authenticates and authorizes every
     * request first, then asks the cache for the already-authorized result.
     */
    private final FileListingCache fileListingCache;

    public FileService(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            FolderRepository folderRepository,
            UserRepository userRepository,
            StorageService storageService,
            FileAccessService fileAccessService,
            FileListingCache fileListingCache) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.fileAccessService = fileAccessService;
        this.fileListingCache = fileListingCache;
    }

    @Transactional
    @MeasuredOperation("upload")
    public FileUploadResponse upload(Authentication authentication, MultipartFile multipartFile, UUID folderId) {
        User owner = currentUser(authentication);
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        long size = multipartFile.getSize();
        if (size < 0) throw new IllegalArgumentException("Size in bytes must be non-negative");
        String name = normalizeAndValidateName(multipartFile.getOriginalFilename());
        String contentType = normalizeAndValidateContentType(
                multipartFile.getContentType() == null || multipartFile.getContentType().isBlank()
                        ? "application/octet-stream" : multipartFile.getContentType());
        Folder folder = resolveOwnedFolder(owner, folderId);
        assertNameAvailable(owner, folder, name);
        String objectKey = buildObjectKey(owner.getId(), name);

        MessageDigest digest = sha256Digest();
        try (var input = new DigestInputStream(multipartFile.getInputStream(), digest)) {
            storageService.upload(objectKey, input, size, contentType);
        } catch (IOException e) {
            throw new StorageException("Unable to read uploaded file", e);
        }

        try {
            String checksum = HexFormat.of().formatHex(digest.digest());
            File file = new File(owner, folder, name, contentType, size, null);
            file.setObjectKey(objectKey);
            file.setChecksum(checksum);
            File saved = saveNewFileReconcilingNameConflict(owner, folder, file);
            // First content for this logical file is version 1. Persisting the
            // version inside the same transaction keeps metadata and history
            // consistent: a failure rolls both back and the object is cleaned up.
            fileVersionRepository.saveAndFlush(
                    new FileVersion(saved, 1, objectKey, name, contentType, size, owner, checksum));
            fileListingCache.evictAfterCommit();
            return FileMapper.toUploadResponse(saved, "UPLOADED", 1);
        } catch (RuntimeException databaseFailure) {
            try {
                storageService.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Failed to clean up Silo object {} after metadata persistence failure", objectKey, cleanupFailure);
                databaseFailure.addSuppressed(cleanupFailure);
            }
            throw databaseFailure;
        }
    }

    @Transactional
    public FileUploadResponse createMetadata(
            Authentication authentication,
            FileMetadataRequest request) {

        User owner = currentUser(authentication);

        String name = normalizeAndValidateName(request.getName());
        String contentType = normalizeAndValidateContentType(request.getContentType());
        long sizeBytes = validateSize(request.getSizeBytes());
        String checksum = normalizeChecksum(request.getChecksum());

        Folder folder = resolveOwnedFolder(owner, request.getFolderId());
        assertNameAvailable(owner, folder, name);

        File file = new File(owner, folder, name, contentType, sizeBytes, checksum);
        // Metadata-only registration stores no content bytes, so it records no
        // version. The object key here is a placeholder reserved for a future
        // upload; the first real content (initial upload or replacement) becomes
        // version 1 for this logical file.
        file.setObjectKey(buildObjectKey(owner.getId()));

        File saved = saveNewFileReconcilingNameConflict(owner, folder, file);

        // Metadata-only registration stores no content, so its current version is 0.
        fileListingCache.evictAfterCommit();

        return FileMapper.toUploadResponse(saved, 0);
    }

    /**
     * Lists the file summaries the caller owns in one folder, or at the owner's
     * root when {@code folderId} is {@code null}.
     *
     * <p>Identity and access are resolved on every request, before the cache is
     * consulted: the caller comes from the authenticated principal and a folder
     * owned by somebody else is rejected with {@code 403} whether or not a
     * cached listing for the caller already exists. The cache therefore only
     * ever answers a request whose authorization has already passed, and a hit
     * can never bypass an ownership check.</p>
     */
    @Transactional(readOnly = true)
    public List<FileResponse> listInFolder(
            Authentication authentication,
            UUID folderId) {

        User owner = currentUser(authentication);

        if (folderId == null) {
            return fileListingCache.findOwnedFileSummaries(owner.getId(), null);
        }

        Folder folder = getOwnedFolder(owner, folderId);

        return fileListingCache.findOwnedFileSummaries(owner.getId(), folder.getId());
    }

    /**
     * Folder-scoped form of {@link #listInFolder}: same owner-scoped query, same
     * cache entry, same authorization ordering.
     */
    @Transactional(readOnly = true)
    public List<FileResponse> listInOwnedFolder(
            Authentication authentication,
            UUID folderId) {

        User owner = currentUser(authentication);
        Folder folder = getOwnedFolder(owner, folderId);

        return fileListingCache.findOwnedFileSummaries(owner.getId(), folder.getId());
    }

    @Transactional(readOnly = true)
    public FileMetadataResponse get(
            Authentication authentication,
            UUID id) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, id);

        if (!access.hasAccess()) {
            throw new ForbiddenException(
                    "You do not have access to this file");
        }

        File file = access.file();
        return FileMapper.toMetadataResponse(file, currentVersion(file.getId()));
    }

    @Transactional
    public FileMetadataResponse update(
            Authentication authentication,
            UUID id,
            FileMetadataUpdateRequest request) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, id);

        if (!access.hasAccess()) {
            throw new ForbiddenException(
                    "You do not have access to this file");
        }

        if (!access.canEdit()) {
            throw new ForbiddenException(
                    "You do not have permission to modify this file");
        }

        File file = access.file();
        User fileOwner = file.getOwner();

        Folder targetFolder = file.getFolder();
        String targetName = file.getName();
        boolean changed = false;

        if (request.getFolderId() != null) {
            boolean sameFolder = file.getFolder() != null
                    && file.getFolder().getId().equals(request.getFolderId());

            if (!sameFolder) {
                // Moving a file is an ownership-level change and stays owner-only;
                // editors may rename but must not relocate another user's file.
                if (!access.isOwner()) {
                    throw new ForbiddenException(
                            "You do not have permission to move this file");
                }
                targetFolder = getOwnedFolder(fileOwner, request.getFolderId());
                changed = true;
            }
        }

        if (request.getName() != null) {
            String name = normalizeAndValidateName(request.getName());
            if (!name.equals(file.getName())) {
                targetName = name;
                changed = true;
            }
        }

        if (changed) {
            assertNameAvailable(fileOwner, targetFolder, targetName);
        }

        file.setFolder(targetFolder);
        file.setName(targetName);

        if (changed) {
            // The rename/move pre-check is also a check-then-write. A concurrent
            // insert into the same (owner, folder, name) target slot turns the
            // commit-time flush of these columns into a filename-uniqueness
            // violation, and without an explicit flush here that failure escapes
            // the method body as a transaction commit error and surfaces to the
            // client as {@code 500}. Flushing inside the method lets the same
            // translation applied to creation handle it.
            try {
                fileRepository.saveAndFlush(file);
            } catch (DataIntegrityViolationException violation) {
                if (isFilenameUniquenessViolation(violation)) {
                    throw new IllegalArgumentException(
                            "A file with this name already exists in this folder");
                }
                throw violation;
            }
        }

        if (request.getContentType() != null) {
            file.setContentType(normalizeAndValidateContentType(request.getContentType()));
        }

        if (request.getSizeBytes() != null) {
            file.setSizeBytes(validateSize(request.getSizeBytes()));
        }

        if (request.getChecksum() != null) {
            file.setChecksum(normalizeChecksum(request.getChecksum()));
        }

        // The file's listing entry can have changed in name, folder, size,
        // content type or version; clear the affected listings after commit.
        fileListingCache.evictAfterCommit();

        return FileMapper.toMetadataResponse(file, currentVersion(file.getId()));
    }

    /**
     * Replaces a file's content with a newly uploaded object and records the
     * result as the next immutable {@link FileVersion}.
     *
     * <p>The new bytes are always written to a fresh object key. The previous
     * key is left untouched on the file's older versions, so replacing content
     * never overwrites historical bytes. The parent {@link File} is then
     * advanced to the new key/content-type/size, which keeps downloads and share
     * links pointing at the newest version. Only the caller's identity and the
     * server-generated object key are trusted; version numbers are derived from
     * existing history rather than client input.</p>
     */
    @Transactional
    @MeasuredOperation("replace")
    public FileUploadResponse replaceContent(
            Authentication authentication,
            UUID id,
            MultipartFile multipartFile) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, id);

        if (!access.hasAccess()) {
            throw new ForbiddenException(
                    "You do not have access to this file");
        }

        if (!access.canEdit()) {
            throw new ForbiddenException(
                    "You do not have permission to modify this file");
        }

        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }

        long size = multipartFile.getSize();
        if (size < 0) {
            throw new IllegalArgumentException("Size in bytes must be non-negative");
        }

        File file = access.file();
        User fileOwner = file.getOwner();

        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = file.getName();
        } else {
            originalFilename = normalizeAndValidateName(originalFilename);
        }
        String contentType = multipartFile.getContentType() == null
                || multipartFile.getContentType().isBlank()
                        ? "application/octet-stream"
                        : multipartFile.getContentType();
        contentType = normalizeAndValidateContentType(contentType);

        String objectKey = buildObjectKey(fileOwner.getId(), originalFilename);

        MessageDigest digest = sha256Digest();
        try (var input = new DigestInputStream(multipartFile.getInputStream(), digest)) {
            storageService.upload(objectKey, input, size, contentType);
        } catch (IOException e) {
            throw new StorageException("Unable to read uploaded file", e);
        }

        try {
            String checksum = HexFormat.of().formatHex(digest.digest());
            File lockedFile = fileRepository.findByIdForUpdate(file.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("File not found"));
            int versionNumber = nextVersionNumber(lockedFile.getId());

            lockedFile.setObjectKey(objectKey);
            lockedFile.setContentType(contentType);
            lockedFile.setSizeBytes(size);
            lockedFile.setChecksum(checksum);

            File saved = fileRepository.saveAndFlush(lockedFile);
            fileVersionRepository.saveAndFlush(new FileVersion(
                    saved,
                    versionNumber,
                    objectKey,
                    originalFilename,
                    contentType,
                    size,
                    caller,
                    checksum));
            fileListingCache.evictAfterCommit();

            return FileMapper.toUploadResponse(saved, "UPLOADED", versionNumber);
        } catch (RuntimeException databaseFailure) {
            // Only the just-uploaded object is removed; every historical version
            // object stays in storage so earlier versions remain recoverable.
            try {
                storageService.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Failed to clean up Silo object {} after version persistence failure",
                        objectKey, cleanupFailure);
                databaseFailure.addSuppressed(cleanupFailure);
            }
            throw databaseFailure;
        }
    }

    /**
     * Persists a brand-new logical file whose bytes were already written to
     * storage by a chunked-upload finalization, recording it as version 1 with
     * the same semantics as the classic single-shot upload.
     *
     * <p>Storage was written by the caller before this method runs because the
     * object write and the database write cannot share a transaction. This
     * method is therefore only the metadata half of the flow: it must run inside
     * the caller's completion transaction (the ambient transaction is joined) so
     * that a failure rolls the metadata back and lets the caller remove the
     * orphaned object. Only server-generated inputs are accepted; the object key
     * and version number never come from the client.</p>
     */
    @Transactional
    public FileUploadResponse persistStoredNewFile(
            User owner,
            Folder folder,
            String name,
            String contentType,
            long sizeBytes,
            String objectKey,
            User createdBy,
            String checksum) {

        assertNameAvailable(owner, folder, name);

        File file = new File(owner, folder, name, contentType, sizeBytes, null);
        file.setObjectKey(objectKey);
        file.setChecksum(checksum);
        File saved = saveNewFileReconcilingNameConflict(owner, folder, file);
        // First content for this logical file is version 1, matching the
        // single-shot upload flow.
        fileVersionRepository.saveAndFlush(new FileVersion(
                saved,
                1,
                objectKey,
                name,
                contentType,
                sizeBytes,
                createdBy,
                checksum));
        fileListingCache.evictAfterCommit();
        return FileMapper.toUploadResponse(saved, "UPLOADED", 1);
    }

    /**
     * Advances an existing file to newly stored content and records the result
     * as the next immutable version, matching the multipart replacement flow:
     * the file keeps its identity, name, folder, owner and permissions, only its
     * current content pointer moves forward, and prior versions keep their own
     * objects untouched.
     *
     * <p>As with {@link #persistStoredNewFile}, the object was already written
     * and this metadata half must run inside the caller's completion
     * transaction.</p>
     */
    @Transactional
    public FileUploadResponse persistStoredReplacement(
            File targetFile,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String objectKey,
            User createdBy,
            String checksum) {

        File lockedFile = fileRepository.findByIdForUpdate(targetFile.getId())
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
        int versionNumber = nextVersionNumber(lockedFile.getId());

        lockedFile.setObjectKey(objectKey);
        lockedFile.setContentType(contentType);
        lockedFile.setSizeBytes(sizeBytes);
        lockedFile.setChecksum(checksum);

        File saved = fileRepository.saveAndFlush(lockedFile);
        fileVersionRepository.saveAndFlush(new FileVersion(
                saved,
                versionNumber,
                objectKey,
                originalFilename,
                contentType,
                sizeBytes,
                createdBy,
                checksum));
        fileListingCache.evictAfterCommit();

        return FileMapper.toUploadResponse(saved, "UPLOADED", versionNumber);
    }

    /**
     * Lists a file's immutable version history, newest first. Any caller with
     * read access (owner, editor or viewer) may list history; the response DTO
     * deliberately omits storage object keys and bucket details.
     */
    @Transactional(readOnly = true)
    public List<FileVersionResponse> listVersions(
            Authentication authentication,
            UUID fileId) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, fileId);

        if (!access.hasAccess()) {
            throw new ForbiddenException("You do not have access to this file");
        }

        return fileVersionRepository
                .findByFileIdOrderByVersionNumberDesc(access.file().getId())
                .stream()
                .map(FileVersionMapper::toResponse)
                .toList();
    }

    /**
     * Opens one historical version's bytes. The lookup is scoped to the file so
     * a version id belonging to another file resolves to "not found", and the
     * stream is read from that version's own immutable object key rather than
     * the current one.
     */
    @Transactional(readOnly = true)
    @MeasuredOperation("download")
    public DownloadedFile downloadVersion(
            Authentication authentication,
            UUID fileId,
            UUID versionId) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, fileId);

        if (!access.hasAccess()) {
            throw new ResourceNotFoundException("File not found");
        }

        FileVersion version = requireVersion(access.file(), versionId);

        try {
            return new DownloadedFile(
                    version.getOriginalFilename(),
                    version.getContentType(),
                    version.getSizeBytes(),
                    storageService.download(version.getObjectKey()));
        } catch (StorageException e) {
            log.error("Unable to download Silo object for version {} of file {}",
                    versionId, fileId, e);
            throw e;
        }
    }

    /**
     * Restores an earlier version as the file's newest content without rewinding
     * history.
     *
     * <p>The selected snapshot's bytes are streamed into a brand-new storage
     * object and recorded as the next {@link FileVersion}; no existing version
     * object is overwritten or deleted, so the restored-from version (and every
     * other one) stays downloadable afterwards. Only the file's current pointer,
     * content type and size move forward. The logical file id, name, folder,
     * owner and permissions are untouched: a historical original filename does
     * not rename the file.</p>
     */
    @Transactional
    @MeasuredOperation("restore")
    public FileMetadataResponse restoreVersion(
            Authentication authentication,
            UUID fileId,
            UUID versionId) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, fileId);

        if (!access.hasAccess()) {
            throw new ResourceNotFoundException("File not found");
        }

        if (!access.canEdit()) {
            throw new ForbiddenException("You do not have permission to restore this file");
        }

        File file = access.file();
        FileVersion version = requireVersion(file, versionId);

        String objectKey = buildObjectKey(
                file.getOwner().getId(), version.getOriginalFilename());

        // Stream the historical object into a fresh key. The full file is never
        // buffered in memory and the original object is only ever read.
        try (var input = storageService.download(version.getObjectKey())) {
            storageService.upload(
                    objectKey, input, version.getSizeBytes(), version.getContentType());
        } catch (IOException e) {
            throw new StorageException("Unable to read stored version", e);
        }

        try {
            File lockedFile = fileRepository.findByIdForUpdate(file.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("File not found"));
            int versionNumber = nextVersionNumber(lockedFile.getId());

            lockedFile.setObjectKey(objectKey);
            lockedFile.setContentType(version.getContentType());
            lockedFile.setSizeBytes(version.getSizeBytes());
            lockedFile.setChecksum(version.getChecksum());

            File saved = fileRepository.saveAndFlush(lockedFile);
            fileVersionRepository.saveAndFlush(new FileVersion(
                    saved,
                    versionNumber,
                    objectKey,
                    version.getOriginalFilename(),
                    version.getContentType(),
                    version.getSizeBytes(),
                    caller,
                    version.getChecksum()));

            fileListingCache.evictAfterCommit();

            return FileMapper.toMetadataResponse(saved, versionNumber);
        } catch (RuntimeException databaseFailure) {
            // Only the just-created restore object is removed; every historical
            // version object is left intact.
            try {
                storageService.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Failed to clean up Silo object {} after restore persistence failure",
                        objectKey, cleanupFailure);
                databaseFailure.addSuppressed(cleanupFailure);
            }
            throw databaseFailure;
        }
    }

    @Transactional
    @MeasuredOperation("delete")
    public void delete(
            Authentication authentication,
            UUID id) {

        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, id);

        if (!access.hasAccess()) {
            throw new ResourceNotFoundException("File not found");
        }

        if (!access.isOwner()) {
            throw new ForbiddenException(
                    "You do not have permission to delete this file");
        }

        File file = access.file();

        // Silo and PostgreSQL cannot share a transaction. Remove the stored object
        // first so a storage failure leaves metadata intact and retryable. Silo's
        // remove operation is safe when the object is already absent.
        //
        // Phase 7.1 retention decision: only the current version's object is
        // removed here. The file's file_versions rows go away through the FK
        // cascade, but their historical storage objects are deliberately left in
        // place. Wiping them here would silently make file deletion destructive
        // to version history, so retention/cleanup is deferred to a later phase.
        storageService.delete(file.getObjectKey());

        try {
            fileRepository.delete(file);
            fileRepository.flush();
            fileListingCache.evictAfterCommit();
        } catch (RuntimeException databaseFailure) {
            // The object cannot be reconstructed without downloading and retaining
            // its bytes, so report the failure and keep the rolled-back row for
            // reconciliation instead of claiming deletion succeeded.
            log.error("Silo object {} was deleted but metadata deletion failed for file {}",
                    file.getObjectKey(), id, databaseFailure);
            throw databaseFailure;
        }
    }

    @Transactional
    @MeasuredOperation("download")
    public DownloadedFile download(Authentication authentication, UUID id) {
        User caller = currentUser(authentication);
        FileAccessService.FileAccess access = fileAccessService.resolve(caller, id);

        if (!access.hasAccess()) {
            throw new ResourceNotFoundException("File not found");
        }

        File file = access.file();

        try {
            return new DownloadedFile(file.getName(), file.getContentType(), file.getSizeBytes(),
                    storageService.download(file.getObjectKey()));
        } catch (StorageException e) {
            log.error("Unable to download Silo object for file {}", id, e);
            throw e;
        }
    }

    public record DownloadedFile(String name, String contentType, long sizeBytes, InputStream inputStream) { }

    private User currentUser(Authentication authentication) {

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found"));
    }

    private Folder getOwnedFolder(User owner, UUID id) {

        Folder folder = folderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Folder not found"));

        if (!folder.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException(
                    "You do not have access to this folder");
        }

        return folder;
    }

    private Folder resolveOwnedFolder(User owner, UUID folderId) {

        if (folderId == null) {
            return null;
        }

        return getOwnedFolder(owner, folderId);
    }

    private void assertNameAvailable(User owner, Folder folder, String name) {

        if (nameExists(owner, folder, name)) {
            throw new IllegalArgumentException(
                    "A file with this name already exists in this folder");
        }
    }

    private boolean nameExists(User owner, Folder folder, String name) {

        return folder != null
                ? fileRepository.existsByOwnerIdAndFolderIdAndName(
                        owner.getId(), folder.getId(), name)
                : fileRepository.existsByOwnerIdAndFolderIdIsNullAndName(
                        owner.getId(), name);
    }

    /**
     * Inserts a brand-new file, translating the database's filename-uniqueness
     * violation into the same client-facing error the availability pre-check
     * raises.
     *
     * <p>{@link #assertNameAvailable} is a check-then-insert and can race with a
     * concurrent create for the same (owner, folder, name); the unique index
     * {@code uq_files_owner_folder_name} is the real guard and rejects the loser.
     * The violation is recognized by its constraint name and translated to the
     * standard {@code 400} "name already exists" response, while any other
     * integrity failure (for example a versioning or key conflict) is rethrown
     * unchanged so it is never mistaken for a filename conflict.</p>
     *
     * <p>The translation deliberately does not re-query availability inside the
     * catch block: after the insert failed, the surrounding PostgreSQL
     * transaction is already aborted, so any further SQL there fails with a
     * spurious error that would surface to the client as {@code 500}. Identifying
     * the violation from the exception itself needs no further statement.</p>
     */
    private File saveNewFileReconcilingNameConflict(
            User owner, Folder folder, File file) {

        try {
            return fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException violation) {
            if (isFilenameUniquenessViolation(violation)) {
                throw new IllegalArgumentException(
                        "A file with this name already exists in this folder");
            }
            throw violation;
        }
    }

    /**
     * Reports whether the exception chain carries the database's
     * filename-uniqueness violation for {@code uq_files_owner_folder_name}.
     *
     * <p>PostgreSQL reports the constraint name in the server error message; H2
     * reports the constraint (upper-cased) in its unique-index violation
     * message. Matching the constraint name case-insensitively across the cause
     * chain is therefore portable, and anything that does not name this index
     * is returned as {@code false} so unrelated integrity failures are never
     * reinterpreted as a client-facing filename conflict.</p>
     */
    private boolean isFilenameUniquenessViolation(Throwable failure) {

        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase()
                    .contains("uq_files_owner_folder_name")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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
            throw new IllegalArgumentException(
                    "File name must not contain path separators");
        }

        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("File name is invalid");
        }

        return normalized;
    }

    private String normalizeAndValidateContentType(String contentType) {

        String normalized = contentType == null ? "" : contentType.trim();

        // Ignore optional parameters such as "; charset=utf-8".
        String base = normalized.split(";", 2)[0].trim();

        if (!CONTENT_TYPE_PATTERN.matcher(base).matches()) {
            throw new IllegalArgumentException("Content type is invalid");
        }

        return base;
    }

    private long validateSize(Long sizeBytes) {

        if (sizeBytes == null || sizeBytes < 0) {
            throw new IllegalArgumentException(
                    "Size in bytes must be a non-negative number");
        }

        return sizeBytes;
    }

    private String normalizeChecksum(String checksum) {

        String normalized = checksum == null ? "" : checksum.trim();

        return normalized.isEmpty() ? null : normalized;
    }

    private String buildObjectKey(UUID ownerId) {

        return "files/" + ownerId + "/" + UUID.randomUUID();
    }

    /**
     * Builds a fresh, never-reused storage key for a stored content object.
     * The random component guarantees a replacement can never collide with an
     * existing version's key.
     */
    private String buildObjectKey(UUID ownerId, String originalFilename) {

        return StorageKeys.contentObjectKey(ownerId, originalFilename);
    }

    /**
     * Next version number for a file, derived from its existing history rather
     * than from client input. A file with no stored content yet starts at 1.
     */
    private int nextVersionNumber(UUID fileId) {

        return fileVersionRepository
                .findTopByFileIdOrderByVersionNumberDesc(fileId)
                .map(version -> version.getVersionNumber() + 1)
                .orElse(1);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Loads a version only if it belongs to the given file; otherwise reports it
     * as not found without revealing that a version with that id exists on
     * another file.
     */
    private FileVersion requireVersion(File file, UUID versionId) {

        return fileVersionRepository.findByIdAndFileId(versionId, file.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
    }

    /** Latest version number for a file, or 0 when it has no stored content. */
    private int currentVersion(UUID fileId) {

        return fileVersionRepository.findTopByFileIdOrderByVersionNumberDesc(fileId)
                .map(FileVersion::getVersionNumber)
                .orElse(0);
    }
}
