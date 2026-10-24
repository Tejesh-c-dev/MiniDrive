package com.minidrive.controller;

import com.minidrive.dto.file.SharedFileResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.User;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.exception.ResourceNotFoundException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

/** Lists only direct permission records belonging to the authenticated user. */
@RestController
public class SharedFilesController {
    private final PermissionRepository permissions;
    private final UserRepository users;
    private final FileVersionRepository versions;
    private final FolderRepository folders;

    public SharedFilesController(PermissionRepository permissions, UserRepository users,
            FileVersionRepository versions, FolderRepository folders) {
        this.permissions = permissions;
        this.users = users;
        this.versions = versions;
        this.folders = folders;
    }

    @GetMapping("/api/files/shared-with-me")
    @Transactional(readOnly = true)
    public ResponseEntity<List<SharedFileResponse>> list(Authentication authentication) {
        User user = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        List<SharedFileResponse> result = permissions.findSharedFilesForUser(user).stream()
                .filter(row -> {
                    File file = (File) row[0];
                    return file.getFolder() == null
                            || !folders.hasDeletingAncestor(file.getFolder().getId());
                })
                .map(row -> {
                    File file = (File) row[0];
                    User owner = (User) row[2];
                    int current = versions.findTopByFileIdOrderByVersionNumberDesc(file.getId())
                            .map(v -> v.getVersionNumber()).orElse(0);
                    return SharedFileResponse.from(file, owner,
                            (com.minidrive.entity.FileRole) row[1], current);
                }).toList();
        return ResponseEntity.ok(result);
    }
}
