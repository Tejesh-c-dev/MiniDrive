package com.minidrive.repository;

import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.Permission;
import com.minidrive.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PermissionRepositoryTest {

    @Autowired private PermissionRepository permissionRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        permissionRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsPermissionWithGeneratedIdAndTimestamps() {
        User owner = persistUser("owner@test.com");
        User collaborator = persistUser("viewer@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");

        Permission saved = permissionRepository.saveAndFlush(
                new Permission(file, collaborator, FileRole.VIEWER));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Permission persisted = permissionRepository.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getFile().getId()).isEqualTo(file.getId());
        assertThat(persisted.getUser().getId()).isEqualTo(collaborator.getId());
        assertThat(persisted.getRole()).isEqualTo(FileRole.VIEWER);
    }

    @Test
    void findsPermissionByFileAndUserAndListsCollaborators() {
        User owner = persistUser("owner@test.com");
        User editor = persistUser("editor@test.com");
        User viewer = persistUser("viewer@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");

        permissionRepository.saveAndFlush(new Permission(file, viewer, FileRole.VIEWER));
        permissionRepository.saveAndFlush(new Permission(file, editor, FileRole.EDITOR));

        assertThat(permissionRepository.findByFileIdAndUserId(file.getId(), viewer.getId()))
                .isPresent()
                .get()
                .extracting(Permission::getRole)
                .isEqualTo(FileRole.VIEWER);
        assertThat(permissionRepository.findByFileIdAndUserId(file.getId(), owner.getId()))
                .isEmpty();
        assertThat(permissionRepository.existsByFileIdAndUserId(file.getId(), editor.getId()))
                .isTrue();

        List<Permission> collaborators = permissionRepository.findByFileIdOrderByCreatedAtAsc(file.getId());
        assertThat(collaborators).hasSize(2);
        assertThat(collaborators).allSatisfy(permission ->
                assertThat(permission.getFile().getId()).isEqualTo(file.getId()));
    }

    @Test
    void rejectsDuplicateFileUserPair() {
        User owner = persistUser("owner@test.com");
        User collaborator = persistUser("viewer@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");

        permissionRepository.saveAndFlush(new Permission(file, collaborator, FileRole.VIEWER));

        assertThatThrownBy(() -> permissionRepository.saveAndFlush(
                new Permission(file, collaborator, FileRole.EDITOR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingFileCascadesToItsPermissions() {
        User owner = persistUser("owner@test.com");
        User collaborator = persistUser("viewer@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");
        permissionRepository.saveAndFlush(new Permission(file, collaborator, FileRole.VIEWER));

        fileRepository.delete(file);
        fileRepository.flush();

        assertThat(permissionRepository.count()).isZero();
    }

    private User persistUser(String email) {
        return userRepository.saveAndFlush(new User("User", email, "hashed-password"));
    }

    private File persistFile(User owner, String name, String objectKey) {
        File file = new File(owner, null, name, "application/pdf", 1024L, null);
        file.setObjectKey(objectKey);
        return fileRepository.saveAndFlush(file);
    }
}
