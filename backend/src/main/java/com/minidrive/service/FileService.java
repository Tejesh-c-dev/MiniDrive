package com.minidrive.service;

import com.minidrive.dto.file.FileMetadataRequest;
import com.minidrive.dto.file.FileMetadataResponse;
import com.minidrive.dto.file.FileMetadataUpdateRequest;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.Folder;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.mapper.FileMapper;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;
import com.minidrive.exception.StorageException;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

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
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public FileService(
            FileRepository fileRepository,
            FolderRepository folderRepository,
            UserRepository userRepository,
            StorageService storageService) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    @Transactional
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
        String objectKey = owner.getId() + "/" + UUID.randomUUID() + "-" + sanitizeFilename(name);

        try (var input = multipartFile.getInputStream()) {
            storageService.upload(objectKey, input, size, contentType);
        } catch (IOException e) {
            throw new StorageException("Unable to read uploaded file", e);
        }

        try {
            File file = new File(owner, folder, name, contentType, size, null);
            file.setObjectKey(objectKey);
            return FileMapper.toUploadResponse(fileRepository.saveAndFlush(file), "UPLOADED");
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

    private String sanitizeFilename(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        return safe.isBlank() ? "file" : safe;
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
        file.setObjectKey(buildObjectKey(owner.getId()));

        return FileMapper.toUploadResponse(fileRepository.save(file));
    }

    @Transactional(readOnly = true)
    public List<FileResponse> listInFolder(
            Authentication authentication,
            UUID folderId) {

        User owner = currentUser(authentication);

        if (folderId == null) {
            return fileRepository
                    .findByOwnerIdAndFolderIdIsNull(owner.getId())
                    .stream()
                    .map(FileMapper::toResponse)
                    .toList();
        }

        Folder folder = getOwnedFolder(owner, folderId);

        return fileRepository
                .findByOwnerIdAndFolderId(owner.getId(), folder.getId())
                .stream()
                .map(FileMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> listInOwnedFolder(
            Authentication authentication,
            UUID folderId) {

        User owner = currentUser(authentication);
        Folder folder = getOwnedFolder(owner, folderId);

        return fileRepository
                .findByOwnerIdAndFolderId(owner.getId(), folder.getId())
                .stream()
                .map(FileMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FileMetadataResponse get(
            Authentication authentication,
            UUID id) {

        User owner = currentUser(authentication);

        return FileMapper.toMetadataResponse(getOwnedFile(owner, id));
    }

    @Transactional
    public FileMetadataResponse update(
            Authentication authentication,
            UUID id,
            FileMetadataUpdateRequest request) {

        User owner = currentUser(authentication);
        File file = getOwnedFile(owner, id);

        Folder targetFolder = file.getFolder();
        String targetName = file.getName();
        boolean changed = false;

        if (request.getFolderId() != null) {
            targetFolder = getOwnedFolder(owner, request.getFolderId());
            if (!targetFolder.equals(file.getFolder())) {
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
            assertNameAvailable(owner, targetFolder, targetName);
        }

        file.setFolder(targetFolder);
        file.setName(targetName);

        if (request.getContentType() != null) {
            file.setContentType(normalizeAndValidateContentType(request.getContentType()));
        }

        if (request.getSizeBytes() != null) {
            file.setSizeBytes(validateSize(request.getSizeBytes()));
        }

        if (request.getChecksum() != null) {
            file.setChecksum(normalizeChecksum(request.getChecksum()));
        }

        return FileMapper.toMetadataResponse(file);
    }

    @Transactional
    public void delete(
            Authentication authentication,
            UUID id) {

        User owner = currentUser(authentication);
        File file = fileRepository.findByIdAndOwnerId(id, owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        // Silo and PostgreSQL cannot share a transaction. Remove the stored object
        // first so a storage failure leaves metadata intact and retryable. Silo's
        // remove operation is safe when the object is already absent.
        storageService.delete(file.getObjectKey());

        try {
            fileRepository.delete(file);
            fileRepository.flush();
        } catch (RuntimeException databaseFailure) {
            // The object cannot be reconstructed without downloading and retaining
            // its bytes, so report the failure and keep the rolled-back row for
            // reconciliation instead of claiming deletion succeeded.
            log.error("Silo object {} was deleted but metadata deletion failed for file {}",
                    file.getObjectKey(), id, databaseFailure);
            throw databaseFailure;
        }
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(Authentication authentication, UUID id) {
        User owner = currentUser(authentication);
        File file = fileRepository.findByIdAndOwnerId(id, owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
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

    private File getOwnedFile(User owner, UUID id) {

        File file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "File not found"));

        if (!file.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException(
                    "You do not have access to this file");
        }

        return file;
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

        boolean taken = folder != null
                ? fileRepository.existsByOwnerIdAndFolderIdAndName(
                        owner.getId(), folder.getId(), name)
                : fileRepository.existsByOwnerIdAndFolderIdIsNullAndName(
                        owner.getId(), name);

        if (taken) {
            throw new IllegalArgumentException(
                    "A file with this name already exists in this folder");
        }
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
}
