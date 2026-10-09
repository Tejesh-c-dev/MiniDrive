package com.minidrive.service;

import com.minidrive.dto.sharelink.CreateShareLinkRequest;
import com.minidrive.dto.sharelink.CreatedShareLinkResponse;
import com.minidrive.dto.sharelink.ShareLinkResponse;
import com.minidrive.dto.sharelink.SharedFileResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.ShareLink;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.exception.StorageException;
import com.minidrive.mapper.ShareLinkMapper;
import com.minidrive.repository.ShareLinkRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.ShareTokenService;
import com.minidrive.storage.StorageService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manages secure, revocable, optionally expiring public share links.
 *
 * <p>Management (create/list/revoke) is owner-only and derives the caller from
 * the authenticated principal, never from client input. Public resolution is
 * anonymous but still enforces token validity and expiration on every request
 * so a link can never be used after it is revoked or expires.</p>
 */
@Service
public class ShareLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShareLinkService.class);

    /**
     * A single, deliberately uniform message for unknown, revoked and expired
     * tokens. Distinguishing the cases would leak whether a file exists and was
     * shared, so every unusable token resolves identically.
     */
    private static final String UNAVAILABLE_MESSAGE =
            "This share link is invalid, expired, or has been revoked";

    private final ShareLinkRepository shareLinkRepository;
    private final UserRepository userRepository;
    private final FileAccessService fileAccessService;
    private final StorageService storageService;
    private final ShareTokenService shareTokenService;

    public ShareLinkService(
            ShareLinkRepository shareLinkRepository,
            UserRepository userRepository,
            FileAccessService fileAccessService,
            StorageService storageService,
            ShareTokenService shareTokenService) {
        this.shareLinkRepository = shareLinkRepository;
        this.userRepository = userRepository;
        this.fileAccessService = fileAccessService;
        this.storageService = storageService;
        this.shareTokenService = shareTokenService;
    }

    /**
     * Creates a link for a file the caller owns. The raw token is returned only
     * in this response; only its hash is persisted.
     */
    @Transactional
    public CreatedShareLinkResponse create(
            Authentication authentication,
            UUID fileId,
            CreateShareLinkRequest request) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        LocalDateTime expiresAt = request == null ? null : request.getExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException(
                    "Expiration time must be in the future");
        }

        String token = shareTokenService.generateToken();
        ShareLink link = new ShareLink(
                file,
                shareTokenService.hash(token),
                expiresAt);

        ShareLink saved = shareLinkRepository.saveAndFlush(link);

        return ShareLinkMapper.toCreatedResponse(saved, token);
    }

    /** Lists a file's links (metadata only) for its owner. */
    @Transactional(readOnly = true)
    public List<ShareLinkResponse> list(
            Authentication authentication,
            UUID fileId) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        return shareLinkRepository
                .findByFileIdOrderByCreatedAtAsc(file.getId())
                .stream()
                .map(ShareLinkMapper::toResponse)
                .toList();
    }

    /**
     * Revokes one of a file's links. The row is deleted, so the token stops
     * resolving immediately.
     */
    @Transactional
    public void revoke(
            Authentication authentication,
            UUID fileId,
            UUID shareId) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        ShareLink link = shareLinkRepository
                .findByIdAndFileId(shareId, file.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));

        shareLinkRepository.delete(link);
    }

    /** Returns safe, minimal metadata for a valid public token. */
    @Transactional(readOnly = true)
    public SharedFileResponse getSharedFile(String token) {
        return ShareLinkMapper.toSharedFileResponse(resolveActiveLink(token).getFile());
    }

    /**
     * Opens the shared object through the existing storage abstraction. The
     * caller streams and closes the returned stream.
     */
    @Transactional(readOnly = true)
    public SharedFileDownload download(String token) {
        File file = resolveActiveLink(token).getFile();

        try {
            return new SharedFileDownload(
                    file.getName(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    storageService.download(file.getObjectKey()));
        } catch (StorageException e) {
            // Never log the raw token; the object key is internal metadata.
            log.error("Unable to download stored object for a shared file", e);
            throw e;
        }
    }

    /**
     * Resolves a raw token to its still-usable link, or rejects it. Unknown,
     * revoked and expired tokens are indistinguishable to the caller.
     */
    private ShareLink resolveActiveLink(String token) {

        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException(UNAVAILABLE_MESSAGE);
        }

        ShareLink link = shareLinkRepository
                .findByTokenHash(shareTokenService.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException(UNAVAILABLE_MESSAGE));

        if (ShareLinkMapper.isExpired(link)) {
            throw new ResourceNotFoundException(UNAVAILABLE_MESSAGE);
        }

        return link;
    }

    private File requireOwnedFile(User caller, UUID fileId) {

        FileAccessService.FileAccess access = fileAccessService.resolve(caller, fileId);

        if (!access.hasAccess()) {
            // Do not reveal whether a file the caller cannot see exists.
            throw new ResourceNotFoundException("File not found");
        }

        if (!access.isOwner()) {
            throw new ForbiddenException(
                    "Only the file owner can manage share links");
        }

        return access.file();
    }

    private User currentUser(Authentication authentication) {

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found"));
    }

    /** A resolved shared file plus its opened storage stream. */
    public record SharedFileDownload(
            String name,
            String contentType,
            long sizeBytes,
            InputStream inputStream) {
    }
}
