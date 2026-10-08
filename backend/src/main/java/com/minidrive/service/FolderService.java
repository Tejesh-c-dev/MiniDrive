package com.minidrive.service;

import com.minidrive.dto.folder.FolderRequest;
import com.minidrive.dto.folder.FolderResponse;
import com.minidrive.entity.Folder;
import com.minidrive.entity.User;
import com.minidrive.exception.ForbiddenException;
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

    public FolderService(
            FolderRepository folderRepository,
            UserRepository userRepository) {
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public FolderResponse create(
            Authentication authentication,
            FolderRequest request) {

        User owner = currentUser(authentication);
        String name = normalizeName(request.getName());
        Folder parent = resolveOwnedParent(owner, request.getParentFolderId());

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

        return FolderMapper.toResponse(getOwnedFolder(owner, id));
    }

    @Transactional
    public FolderResponse rename(
            Authentication authentication,
            UUID id,
            FolderRequest request) {

        User owner = currentUser(authentication);
        Folder folder = getOwnedFolder(owner, id);

        String name = normalizeName(request.getName());

        if (!name.equals(folder.getName())) {
            assertNameAvailable(owner, folder.getParentFolder(), name);
            folder.setName(name);
        }

        return FolderMapper.toResponse(folder);
    }

    @Transactional
    public void delete(
            Authentication authentication,
            UUID id) {

        User owner = currentUser(authentication);
        Folder folder = getOwnedFolder(owner, id);

        folderRepository.delete(folder);
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
