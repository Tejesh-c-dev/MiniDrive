package com.minidrive.service;

import com.minidrive.dto.permission.PermissionRequest;
import com.minidrive.dto.permission.PermissionResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.Permission;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.mapper.PermissionMapper;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UserRepository;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages direct, file-scoped permissions. Only the file's owner may list,
 * grant or revoke access, and the caller is always derived from the
 * authenticated principal rather than any client-supplied identity.
 */
@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final FileAccessService fileAccessService;

    public PermissionService(
            PermissionRepository permissionRepository,
            UserRepository userRepository,
            FileAccessService fileAccessService) {
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.fileAccessService = fileAccessService;
    }

    /**
     * Grants access to a registered user, or updates their role when they
     * already have access. Only {@code EDITOR} and {@code VIEWER} may be
     * granted; ownership can never be handed out or duplicated.
     */
    @Transactional
    public PermissionResponse grant(
            Authentication authentication,
            UUID fileId,
            PermissionRequest request) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        FileRole role = request.getRole();
        if (role != FileRole.VIEWER && role != FileRole.EDITOR) {
            throw new IllegalArgumentException(
                    "Role must be either VIEWER or EDITOR");
        }

        User recipient = userRepository
                .findByEmail(normalizeEmail(request.getEmail()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (recipient.getId().equals(file.getOwner().getId())) {
            throw new IllegalArgumentException(
                    "The file owner already has full access to this file");
        }

        Permission permission = permissionRepository
                .findByFileIdAndUserId(file.getId(), recipient.getId())
                .orElseGet(() -> new Permission(file, recipient, role));

        permission.setRole(role);

        // Flush so lifecycle callbacks populate timestamps and the unique
        // (file_id, user_id) constraint is enforced within this transaction.
        return PermissionMapper.toResponse(permissionRepository.saveAndFlush(permission));
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> list(
            Authentication authentication,
            UUID fileId) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        return permissionRepository
                .findByFileIdOrderByCreatedAtAsc(file.getId())
                .stream()
                .map(PermissionMapper::toResponse)
                .toList();
    }

    /**
     * Revokes a user's direct access. The owner's access cannot be revoked or
     * transferred because ownership is not stored as a permission row.
     */
    @Transactional
    public void revoke(
            Authentication authentication,
            UUID fileId,
            UUID userId) {

        User caller = currentUser(authentication);
        File file = requireOwnedFile(caller, fileId);

        if (file.getOwner().getId().equals(userId)) {
            throw new IllegalArgumentException(
                    "The file owner's access cannot be revoked");
        }

        Permission permission = permissionRepository
                .findByFileIdAndUserId(file.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found"));

        permissionRepository.delete(permission);
    }

    private File requireOwnedFile(User caller, UUID fileId) {

        FileAccessService.FileAccess access = fileAccessService.resolve(caller, fileId);

        if (!access.hasAccess()) {
            // Do not reveal whether a file the caller cannot see exists.
            throw new ResourceNotFoundException("File not found");
        }

        if (!access.isOwner()) {
            throw new ForbiddenException(
                    "Only the file owner can manage permissions");
        }

        return access.file();
    }

    private User currentUser(Authentication authentication) {

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found"));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
