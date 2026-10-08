package com.minidrive.repository;

import com.minidrive.entity.File;
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
class FileRepositoryTest {

    @Autowired private FileRepository fileRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        fileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsFileMetadataWithGeneratedIdAndCreatedAt() {
        User owner = persistUser("owner@test.com");
        String objectKey = "files/" + owner.getId() + "/object-1";
        File file = newFile(owner, "report.pdf", "application/pdf", 1024L, objectKey);

        File saved = fileRepository.saveAndFlush(file);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        File persisted = fileRepository.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("report.pdf");
        assertThat(persisted.getObjectKey()).isEqualTo(objectKey);
        assertThat(persisted.getContentType()).isEqualTo("application/pdf");
        assertThat(persisted.getSizeBytes()).isEqualTo(1024L);
    }

    @Test
    void linksEveryFileToItsOwningUser() {
        User owner = persistUser("owner@test.com");
        File saved = fileRepository.saveAndFlush(
                newFile(owner, "notes.txt", "text/plain", 12L, "files/" + owner.getId() + "/object-owner"));

        File persisted = fileRepository.findById(saved.getId()).orElseThrow();

        assertThat(persisted.getOwner()).isNotNull();
        assertThat(persisted.getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test
    void findsAllFilesBelongingToAUser() {
        User alice = persistUser("alice@test.com");
        User bob = persistUser("bob@test.com");
        fileRepository.saveAndFlush(newFile(alice, "alice-1.txt", "text/plain", 1L, "files/alice/1"));
        fileRepository.saveAndFlush(newFile(alice, "alice-2.txt", "text/plain", 2L, "files/alice/2"));
        fileRepository.saveAndFlush(newFile(bob, "bob-1.txt", "text/plain", 3L, "files/bob/1"));

        List<File> aliceFiles = fileRepository.findByOwnerId(alice.getId());

        assertThat(aliceFiles).hasSize(2);
        assertThat(aliceFiles).allSatisfy(file ->
                assertThat(file.getOwner().getId()).isEqualTo(alice.getId()));
    }

    @Test
    void findsFileByObjectKeyAndReportsObjectKeyExistence() {
        User owner = persistUser("owner@test.com");
        fileRepository.saveAndFlush(newFile(owner, "a.txt", "text/plain", 1L, "files/owner/key"));

        assertThat(fileRepository.findByObjectKey("files/owner/key")).isPresent();
        assertThat(fileRepository.existsByObjectKey("files/owner/key")).isTrue();
        assertThat(fileRepository.findByObjectKey("files/owner/missing")).isEmpty();
        assertThat(fileRepository.existsByObjectKey("files/owner/missing")).isFalse();
    }

    @Test
    void rejectsDuplicateObjectKey() {
        User owner = persistUser("owner@test.com");
        fileRepository.saveAndFlush(newFile(owner, "first.txt", "text/plain", 1L, "files/owner/duplicate"));

        File duplicate = newFile(owner, "second.txt", "text/plain", 2L, "files/owner/duplicate");

        assertThatThrownBy(() -> fileRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User persistUser(String email) {
        return userRepository.saveAndFlush(new User("Owner", email, "hashed-password"));
    }

    private File newFile(
            User owner,
            String name,
            String contentType,
            long sizeBytes,
            String objectKey) {
        File file = new File(owner, null, name, contentType, sizeBytes, null);
        file.setObjectKey(objectKey);
        return file;
    }
}
