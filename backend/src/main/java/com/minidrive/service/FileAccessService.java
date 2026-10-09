package com.minidrive.service;

import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.Permission;
import com.minidrive.entity.User;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.PermissionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the effective {@link FileRole} a user holds for a file and backs the
 * shared, service-level authorization used by every file operation. Keeping the
 * lookup here prevents permission logic from being duplicated across
 * controllers and keeps direct API calls protected, not just hidden UI controls.
 *
 * <p>Ownership lives on {@link File#getOwner()} and is authoritative; a
 * collaborator's role comes from their direct {@link Permission} row. A user
 * with no row and no ownership resolves to a {@code null} role.</p>
 */
@Service
public class FileAccessService {

    private final FileRepository fileRepository;
    private final PermissionRepository permissionRepository;

    public FileAccessService(
            FileRepository fileRepository,
            PermissionRepository permissionRepository) {
        this.fileRepository = fileRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * Resolves the caller's access to a file. Throws
     * {@link ResourceNotFoundException} only when the file itself does not
     * exist; an existing file the caller cannot access resolves to a
     * {@link FileAccess} with a {@code null} role so the caller decides whether
     * to answer {@code 403} or {@code 404}.
     */
    @Transactional(readOnly = true)
    public FileAccess resolve(User caller, UUID fileId) {

        Optional<File> owned = fileRepository.findByIdAndOwnerId(fileId, caller.getId());
        if (owned.isPresent()) {
            return new FileAccess(owned.get(), FileRole.OWNER);
        }

        File file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        FileRole role = permissionRepository
                .findByFileIdAndUserId(fileId, caller.getId())
                .map(Permission::getRole)
                .orElse(null);

        return new FileAccess(file, role);
    }

    /**
     * A resolved file together with the role the caller actually holds. A
     * {@code null} role means the caller has no access at all.
     */
    public record FileAccess(File file, FileRole role) {

        public boolean hasAccess() {
            return role != null;
        }

        public boolean isOwner() {
            return role == FileRole.OWNER;
        }

        /** Owners, editors and viewers may read metadata and download. */
        public boolean canRead() {
            return role != null;
        }

        /** Only owners and editors may change a file. */
        public boolean canEdit() {
            return role == FileRole.OWNER || role == FileRole.EDITOR;
        }
    }
}
