package com.minidrive.repository;

import com.minidrive.entity.File;
import com.minidrive.entity.ShareLink;
import com.minidrive.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ShareLinkRepositoryTest {

    @Autowired private ShareLinkRepository shareLinkRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        shareLinkRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsLinkWithGeneratedIdTimestampAndOptionalExpiry() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");

        ShareLink saved = shareLinkRepository.saveAndFlush(
                new ShareLink(file, hash('a'), null));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getExpiresAt()).isNull();

        ShareLink persisted = shareLinkRepository.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getFile().getId()).isEqualTo(file.getId());
        assertThat(persisted.getTokenHash()).isEqualTo(hash('a'));
    }

    @Test
    void findsLinkByTokenHashAndNeverStoresTheRawToken() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");
        String rawToken = "s3cr3t-raw-bearer-token";
        String storedHash = "00000000000000000000000000000000000000000000000000000000000000ab";

        shareLinkRepository.saveAndFlush(new ShareLink(file, storedHash, null));

        assertThat(shareLinkRepository.findByTokenHash(storedHash)).isPresent();
        assertThat(shareLinkRepository.existsByTokenHash(storedHash)).isTrue();
        // The raw token is never persisted as a lookup key.
        assertThat(shareLinkRepository.findByTokenHash(rawToken)).isEmpty();
    }

    @Test
    void rejectsDuplicateTokenHash() {
        User owner = persistUser("owner@test.com");
        File first = persistFile(owner, "a.txt", "files/owner/a");
        File second = persistFile(owner, "b.txt", "files/owner/b");
        String storedHash = "11111111111111111111111111111111111111111111111111111111111111aa";

        shareLinkRepository.saveAndFlush(new ShareLink(first, storedHash, null));

        assertThatThrownBy(() -> shareLinkRepository.saveAndFlush(
                new ShareLink(second, storedHash, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listsLinksByFileOldestFirstAndScopesLookupByFile() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");
        File other = persistFile(owner, "other.pdf", "files/owner/other");

        ShareLink first = shareLinkRepository.saveAndFlush(
                new ShareLink(file, hash('1'), null));
        ShareLink second = shareLinkRepository.saveAndFlush(
                new ShareLink(file, hash('2'), LocalDateTime.now().plusDays(1)));
        ShareLink otherFileLink = shareLinkRepository.saveAndFlush(
                new ShareLink(other, hash('3'), null));

        assertThat(shareLinkRepository.findByFileIdOrderByCreatedAtAsc(file.getId()))
                .extracting(ShareLink::getId)
                .containsExactly(first.getId(), second.getId());

        assertThat(shareLinkRepository.findByIdAndFileId(second.getId(), file.getId()))
                .isPresent();
        // A link id is scoped to its own file and cannot be resolved through another.
        assertThat(shareLinkRepository.findByIdAndFileId(otherFileLink.getId(), file.getId()))
                .isEmpty();
    }

    @Test
    void deletingFileCascadesToItsShareLinks() {
        User owner = persistUser("owner@test.com");
        File file = persistFile(owner, "report.pdf", "files/owner/report");
        shareLinkRepository.saveAndFlush(new ShareLink(file, hash('a'), null));

        fileRepository.delete(file);
        fileRepository.flush();

        assertThat(shareLinkRepository.count()).isZero();
    }

    private String hash(char filler) {
        return String.valueOf(filler).repeat(64);
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
