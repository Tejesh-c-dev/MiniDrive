package com.minidrive.repository;

import com.minidrive.entity.File;
import com.minidrive.entity.FileVersion;
import com.minidrive.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FileVersionRepositoryTest {

    @Autowired private FileVersionRepository fileVersionRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        fileVersionRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsImmutableVersionMetadataWithGeneratedIdAndTimestamp() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "files/owner/current");

        FileVersion saved = fileVersionRepository.saveAndFlush(
                new FileVersion(file, 1, "files/owner/v1", "report.pdf", "application/pdf", 1024L, owner));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        FileVersion persisted = fileVersionRepository.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getFile().getId()).isEqualTo(file.getId());
        assertThat(persisted.getVersionNumber()).isEqualTo(1);
        assertThat(persisted.getObjectKey()).isEqualTo("files/owner/v1");
        assertThat(persisted.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(persisted.getCreatedBy().getId()).isEqualTo(owner.getId());
    }

    @Test
    void findsLatestVersionAndOrdersHistory() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "files/owner/current");
        saveVersion(file, owner, 1, "files/owner/v1");
        saveVersion(file, owner, 2, "files/owner/v2");
        saveVersion(file, owner, 3, "files/owner/v3");

        assertThat(fileVersionRepository.findTopByFileIdOrderByVersionNumberDesc(file.getId()))
                .get()
                .extracting(FileVersion::getVersionNumber)
                .isEqualTo(3);
        assertThat(fileVersionRepository.findByFileIdOrderByVersionNumberAsc(file.getId()))
                .extracting(FileVersion::getVersionNumber)
                .containsExactly(1, 2, 3);
        assertThat(fileVersionRepository.countByFileId(file.getId())).isEqualTo(3);
        assertThat(fileVersionRepository.existsByFileIdAndVersionNumber(file.getId(), 2)).isTrue();
        assertThat(fileVersionRepository.existsByFileIdAndVersionNumber(file.getId(), 4)).isFalse();
    }

    @Test
    void rejectsDuplicateVersionNumberForTheSameFile() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "files/owner/current");
        saveVersion(file, owner, 1, "files/owner/v1");

        FileVersion duplicate = new FileVersion(
                file, 1, "files/owner/duplicate", "report.pdf", "application/pdf", 10L, owner);

        assertThatThrownBy(() -> fileVersionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsVersionOnlyWithinItsOwningFile() {
        User owner = persistUser("owner@test.com");
        File fileA = persistFile(owner, "files/owner/a");
        File fileB = persistFile(owner, "files/owner/b");
        FileVersion versionA = saveVersionReturning(fileA, owner, 1, "files/owner/a-v1");
        saveVersion(fileB, owner, 1, "files/owner/b-v1");

        assertThat(fileVersionRepository.findByIdAndFileId(versionA.getId(), fileA.getId()))
                .isPresent();
        assertThat(fileVersionRepository.findByIdAndFileId(versionA.getId(), fileB.getId()))
                .isEmpty();
    }

    @Test
    void resolvesCurrentVersionPerFileInOneQuery() {
        User owner = persistUser("owner@test.com");
        File fileA = persistFile(owner, "files/owner/a");
        File fileB = persistFile(owner, "files/owner/b");
        saveVersion(fileA, owner, 1, "files/owner/a-v1");
        saveVersion(fileA, owner, 2, "files/owner/a-v2");
        saveVersion(fileB, owner, 1, "files/owner/b-v1");

        Map<UUID, Integer> currentVersions = fileVersionRepository
                .findCurrentVersions(List.of(fileA.getId(), fileB.getId()))
                .stream()
                .collect(Collectors.toMap(
                        FileVersionRepository.CurrentVersionProjection::getFileId,
                        FileVersionRepository.CurrentVersionProjection::getCurrentVersion));

        assertThat(currentVersions)
                .containsEntry(fileA.getId(), 2)
                .containsEntry(fileB.getId(), 1);
    }

    private void saveVersion(File file, User owner, int number, String objectKey) {
        saveVersionReturning(file, owner, number, objectKey);
    }

    private FileVersion saveVersionReturning(
            File file, User owner, int number, String objectKey) {
        return fileVersionRepository.saveAndFlush(
                new FileVersion(file, number, objectKey, "report.pdf", "application/pdf", 1L, owner));
    }

    private User persistUser(String email) {
        return userRepository.saveAndFlush(new User("Owner", email, "hashed-password"));
    }

    private File persistFile(User owner, String objectKey) {
        File file = new File(owner, null, "report.pdf", "application/pdf", 1L, null);
        file.setObjectKey(objectKey);
        return fileRepository.saveAndFlush(file);
    }
}
