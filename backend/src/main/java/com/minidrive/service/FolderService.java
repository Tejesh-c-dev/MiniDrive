package com.minidrive.service;

import com.minidrive.dto.folder.FolderRequest;
import com.minidrive.dto.folder.FolderResponse;
import com.minidrive.entity.Folder;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
import com.minidrive.exception.FolderDeletionInProgressException;
import com.minidrive.exception.ResourceNotFoundException;
import com.minidrive.mapper.FolderMapper;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final UserRepository userRepository;

    private final FolderDeletionLifecycle deletionLifecycle;

    public FolderService(
            FolderRepository folderRepository,
            UserRepository userRepository,
            FolderDeletionLifecycle deletionLifecycle) {
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.deletionLifecycle = deletionLifecycle;
    }

    @Transactional
    public FolderResponse create(
            Authentication authentication,
            FolderRequest request) {

        User owner = currentUser(authentication);
        userRepository.findByIdForUpdate(owner.getId()).orElseThrow();
        String name = normalizeName(request.getName());
        Folder parent = resolveOwnedParent(owner, request.getParentFolderId());
        assertFolderWritable(parent);

        assertNameAvailable(owner, parent, name);

        Folder folder = new Folder(owner, parent, name);

        return FolderMapper.toResponse(folderRepository.save(folder));
    }

    @Transactional(readOnly = true)
    public List<FolderResponse> listChildren(
            Authentication authentication,
            UUID parentFolderId) {

        User owner = currentUser(authentication);

        if (parentFolderId == null) {
            return folderRepository
                    .findByOwnerIdAndParentFolderIdIsNull(owner.getId())
                    .stream()
                    .map(FolderMapper::toResponse)
                    .toList();
        }

        Folder parent = getOwnedFolder(owner, parentFolderId);
        assertFolderWritable(parent);

        return folderRepository
                .findByOwnerIdAndParentFolderId(owner.getId(), parent.getId())
                .stream()
                .map(FolderMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FolderResponse get(
            Authentication authentication,
            UUID id) {

        User owner = currentUser(authentication);

        Folder folder = getOwnedFolder(owner, id);
        assertFolderWritable(folder);
        return FolderMapper.toResponse(folder);
    }

    @Transactional
    public FolderResponse rename(
            Authentication authentication,
            UUID id,
            FolderRequest request) {

        User owner = currentUser(authentication);
        userRepository.findByIdForUpdate(owner.getId()).orElseThrow();
        Folder folder = getOwnedFolder(owner, id);
        assertFolderWritable(folder);

        String name = normalizeName(request.getName());

        if (!name.equals(folder.getName())) {
            assertNameAvailable(owner, folder.getParentFolder(), name);
            folder.setName(name);
        }

        return FolderMapper.toResponse(folder);
    }

    public DeletionResult delete(
            Authentication authentication,
            UUID id) {

        User owner = currentUser(authentication);
        Folder folder = getOwnedFolder(owner, id);

        UUID jobId = deletionLifecycle.request(folder.getId(), owner.getId());
        if (jobId == null) return new DeletionResult(null, true, "COMPLETED");
        boolean complete = deletionLifecycle.attemptIfDue(jobId);
        return new DeletionResult(jobId, complete, complete ? "COMPLETED" : "PENDING");
    }

    public record DeletionResult(UUID jobId, boolean complete, String status) { }

    /** Reject writes targeting a folder within a subtree already being removed. */
    private void assertFolderWritable(Folder folder) {
        if (folder != null && folderRepository.hasDeletingAncestor(folder.getId()))
            throw new FolderDeletionInProgressException();
    }

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

    private Folder resolveOwnedParent(User owner, UUID parentFolderId) {

        if (parentFolderId == null) {
            return null;
        }

        return getOwnedFolder(owner, parentFolderId);
    }

    private void assertNameAvailable(User owner, Folder parent, String name) {

        boolean taken = parent != null
                ? folderRepository.existsByOwnerIdAndParentFolderIdAndName(
                        owner.getId(), parent.getId(), name)
                : folderRepository.existsByOwnerIdAndParentFolderIdIsNullAndName(
                        owner.getId(), name);

        if (taken) {
            throw new IllegalArgumentException(
                    "A folder with this name already exists in this location");
        }
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }
}
