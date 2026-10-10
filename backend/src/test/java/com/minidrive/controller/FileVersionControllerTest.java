package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.FileVersion;
import com.minidrive.entity.Permission;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7.1: verifies that content uploads and replacements produce immutable,
 * monotonically numbered {@link FileVersion} rows while the mutable file record
 * keeps pointing at the newest content.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileVersionControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @MockitoSpyBean private FileRepository fileRepository;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        fileVersionRepository.deleteAll();
        permissionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void initialUploadCreatesVersionOne() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] bytes = "first-version-content".getBytes();

        String fileId = uploadAndGetId(token, "report.pdf", "application/pdf", bytes);

        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(1);

        FileVersion version = versions.get(0);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(version.getContentType()).isEqualTo("application/pdf");
        assertThat(version.getSizeBytes()).isEqualTo(bytes.length);
        assertThat(version.getCreatedAt()).isNotNull();
        assertThat(version.getCreatedBy().getId())
                .isEqualTo(UUID.fromString(userId("alice@test.com")));

        File file = reload(fileId);
        // The current record points at the same immutable object as version 1.
        assertThat(version.getObjectKey()).isEqualTo(file.getObjectKey());
    }

    @Test
    void replacingContentCreatesVersionTwoAndPreservesVersionOne() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = uploadAndGetId(token, "report.pdf", "application/pdf", "first".getBytes());
        FileVersion version1 = onlyVersion(fileId);
        byte[] replacement = "second-version-content".getBytes();

        replaceContent(token, fileId, "report.pdf", "application/pdf", replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.objectKey").isNotEmpty());

        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(2);

        // Version 1 is untouched, including its storage key.
        assertThat(versions.get(0).getId()).isEqualTo(version1.getId());
        assertThat(versions.get(0).getObjectKey()).isEqualTo(version1.getObjectKey());

        FileVersion version2 = versions.get(1);
        assertThat(version2.getVersionNumber()).isEqualTo(2);
        assertThat(version2.getObjectKey()).isNotEqualTo(version1.getObjectKey());
        assertThat(version2.getSizeBytes()).isEqualTo(replacement.length);

        File file = reload(fileId);
        assertThat(file.getObjectKey()).isEqualTo(version2.getObjectKey());
        assertThat(file.getSizeBytes()).isEqualTo(replacement.length);

        // Replacing content must never delete an earlier version's object.
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .delete(version1.getObjectKey());
    }

    @Test
    void secondReplacementCreatesVersionThreeAndDownloadReturnsCurrentContent() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = uploadAndGetId(token, "notes.txt", "text/plain", "one".getBytes());

        replaceContent(token, fileId, "notes.txt", "text/plain", "two".getBytes())
                .andExpect(status().isOk());
        byte[] latest = "three".getBytes();
        replaceContent(token, fileId, "notes.txt", "text/plain", latest)
                .andExpect(status().isOk());

        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(3);
        assertThat(versions).extracting(FileVersion::getVersionNumber)
                .containsExactly(1, 2, 3);
        assertThat(versions).extracting(FileVersion::getObjectKey)
                .doesNotHaveDuplicates();

        FileVersion version3 = versions.get(2);
        assertThat(version3.getSizeBytes()).isEqualTo(latest.length);

        File file = reload(fileId);
        assertThat(file.getObjectKey()).isEqualTo(version3.getObjectKey());

        org.mockito.Mockito.when(storageService.download(version3.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(latest));
        var result = mockMvc.perform(get("/api/files/" + fileId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("three");
    }

    @Test
    void metadataOnlyRenameDoesNotCreateVersion() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = uploadAndGetId(token, "draft.txt", "text/plain", "body".getBytes());

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"final.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("final.txt"));

        assertThat(reload(fileId).getName()).isEqualTo("final.txt");
        assertThat(versionsOf(fileId)).hasSize(1);
        assertThat(onlyVersion(fileId).getVersionNumber()).isEqualTo(1);
    }

    @Test
    void unauthorizedUserCannotReplaceContent() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String fileId = uploadAndGetId(alice, "secret.txt", "text/plain", "original".getBytes());
        FileVersion version1 = onlyVersion(fileId);
        File before = reload(fileId);

        replaceContent(bob, fileId, "secret.txt", "text/plain", "hijacked".getBytes())
                .andExpect(status().isForbidden());

        assertThat(versionsOf(fileId)).hasSize(1);
        assertThat(onlyVersion(fileId).getObjectKey()).isEqualTo(version1.getObjectKey());
        File after = reload(fileId);
        assertThat(after.getObjectKey()).isEqualTo(before.getObjectKey());
        assertThat(after.getSizeBytes()).isEqualTo(before.getSizeBytes());
    }

    @Test
    void viewerCannotReplaceContentButEditorCan() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String carol = registerAndGetToken("Carol", "carol@test.com");
        String fileId = uploadAndGetId(alice, "shared.txt", "text/plain", "original".getBytes());

        File file = reload(fileId);
        grantRole(file, "bob@test.com", FileRole.VIEWER);
        grantRole(file, "carol@test.com", FileRole.EDITOR);

        replaceContent(bob, fileId, "shared.txt", "text/plain", "viewer-edit".getBytes())
                .andExpect(status().isForbidden());
        assertThat(versionsOf(fileId)).hasSize(1);

        replaceContent(carol, fileId, "shared.txt", "text/plain", "editor-edit".getBytes())
                .andExpect(status().isOk());

        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(2);
        // The editor who uploaded the content is recorded as its creator, even
        // though Alice still owns the file.
        assertThat(versions.get(1).getCreatedBy().getId())
                .isEqualTo(UUID.fromString(userId("carol@test.com")));
        assertThat(reload(fileId).getObjectKey()).isEqualTo(versions.get(1).getObjectKey());
    }

    @Test
    void failedStorageUploadDoesNotCreateVersionAndLeavesFileUntouched() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = uploadAndGetId(token, "keep.txt", "text/plain", "original".getBytes());
        File before = reload(fileId);
        FileVersion version1 = onlyVersion(fileId);

        org.mockito.Mockito.doThrow(new com.minidrive.exception.StorageException("failed"))
                .when(storageService).upload(anyString(), any(), anyLong(), anyString());

        replaceContent(token, fileId, "keep.txt", "text/plain", "never-stored".getBytes())
                .andExpect(status().isBadGateway());

        assertThat(versionsOf(fileId)).hasSize(1);
        assertThat(onlyVersion(fileId).getObjectKey()).isEqualTo(version1.getObjectKey());
        File after = reload(fileId);
        assertThat(after.getObjectKey()).isEqualTo(before.getObjectKey());
        assertThat(after.getSizeBytes()).isEqualTo(before.getSizeBytes());
    }

    @Test
    void versionPersistenceFailureCleansUpOnlyTheNewObjectAndKeepsHistory() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = uploadAndGetId(token, "atomic.txt", "text/plain", "original".getBytes());
        FileVersion version1 = onlyVersion(fileId);
        File before = reload(fileId);

        var databaseFailure = new org.springframework.dao.DataIntegrityViolationException("simulated");
        org.mockito.Mockito.doThrow(databaseFailure)
                .when(fileRepository).saveAndFlush(any(File.class));

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,
                () -> replaceContent(token, fileId, "atomic.txt", "text/plain", "new".getBytes()));

        // History is intact and the current record still points at version 1.
        assertThat(versionsOf(fileId)).hasSize(1);
        assertThat(onlyVersion(fileId).getObjectKey()).isEqualTo(version1.getObjectKey());
        assertThat(reload(fileId).getObjectKey()).isEqualTo(before.getObjectKey());

        // The orphaned object created by the failed replacement is removed, but
        // the pre-existing version object is never deleted.
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .delete(version1.getObjectKey());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.times(1))
                .delete(org.mockito.ArgumentMatchers.argThat(
                        key -> !key.equals(version1.getObjectKey()) && key.contains("atomic.txt")));
    }

    @Test
    void unauthenticatedReplacementIsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/files/" + UUID.randomUUID() + "/content")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", new byte[]{1})))
                .andExpect(status().isUnauthorized());
    }

    private void grantRole(File file, String email, FileRole role) {
        var user = userRepository.findByEmail(email).orElseThrow();
        permissionRepository.saveAndFlush(new Permission(file, user, role));
    }

    private ResultActions replaceContent(
            String token,
            String fileId,
            String name,
            String contentType,
            byte[] bytes) throws Exception {

        return mockMvc.perform(multipart("/api/files/" + fileId + "/content")
                .file(new MockMultipartFile("file", name, contentType, bytes))
                .header("Authorization", "Bearer " + token));
    }

    private String uploadAndGetId(String token, String name, String contentType, byte[] bytes)
            throws Exception {

        String body = mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", name, contentType, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private List<FileVersion> versionsOf(String fileId) {
        return fileVersionRepository.findByFileIdOrderByVersionNumberAsc(UUID.fromString(fileId));
    }

    private FileVersion onlyVersion(String fileId) {
        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(1);
        return versions.get(0);
    }

    private File reload(String fileId) {
        return fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
    }

    private String userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId().toString();
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"token\":\"")[1].split("\"")[0];
    }
}
