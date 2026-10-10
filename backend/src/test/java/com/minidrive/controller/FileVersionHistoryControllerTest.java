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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7.2: verifies the version history, version download and restore APIs,
 * including authorization, ordering and the invariant that restoring an older
 * version appends a new version instead of rewinding or destroying history.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileVersionHistoryControllerTest {

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
    void historyListsVersionsNewestFirstWithCreatorInfoAndNoStorageKeys() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] first = "first-version".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-version-longer".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(token, "report.pdf", "application/pdf", first);
        replaceContent(token, fileId, "report.pdf", "application/pdf", second)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/files/" + fileId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[1].versionNumber").value(1))
                .andExpect(jsonPath("$[0].originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$[0].sizeBytes").value(second.length))
                .andExpect(jsonPath("$[0].createdByName").value("Alice"))
                .andExpect(jsonPath("$[0].createdByEmail").value("alice@test.com"))
                .andExpect(jsonPath("$[0].createdById").value(userId("alice@test.com")))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].sizeBytes").value(first.length))
                // Internal storage details must never leak through the API.
                .andExpect(jsonPath("$[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    void nonCollaboratorCannotListHistoryButOwnerCan() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String fileId = uploadAndGetId(alice, "private.txt", "text/plain", "secret".getBytes());

        mockMvc.perform(get("/api/files/" + fileId + "/versions")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/files/" + fileId + "/versions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // An unknown file is reported as not found, not forbidden.
        mockMvc.perform(get("/api/files/" + UUID.randomUUID() + "/versions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/files/" + fileId + "/versions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadingOlderVersionReturnsItsOriginalBytes() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(token, "notes.txt", "text/plain", first);
        FileVersion version1 = version(fileId, 1);
        replaceContent(token, fileId, "notes.txt", "text/plain", second)
                .andExpect(status().isOk());

        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));

        MvcResult result = mockMvc.perform(get("/api/files/" + fileId
                        + "/versions/" + version1.getId() + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyOf(result)).isEqualTo("one");
        // The bytes come from the version's own immutable key, not the current one.
        verify(storageService).download(version1.getObjectKey());
        verify(storageService, never()).download(reload(fileId).getObjectKey());
    }

    @Test
    void versionIdBelongingToAnotherFileIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileA = uploadAndGetId(token, "a.txt", "text/plain", "a".getBytes());
        String fileB = uploadAndGetId(token, "b.txt", "text/plain", "b".getBytes());
        FileVersion versionOfA = version(fileA, 1);

        mockMvc.perform(get("/api/files/" + fileB + "/versions/"
                        + versionOfA.getId() + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/files/" + fileB + "/versions/"
                        + versionOfA.getId() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        verify(storageService, never()).download(versionOfA.getObjectKey());
        assertThat(versionsOf(fileB)).hasSize(1);
    }

    @Test
    void viewerCanReadHistoryAndDownloadButCannotRestore() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(alice, "shared.txt", "text/plain", first);
        FileVersion version1 = version(fileId, 1);
        replaceContent(alice, fileId, "shared.txt", "text/plain", "second".getBytes())
                .andExpect(status().isOk());
        grantRole(reload(fileId), "bob@test.com", FileRole.VIEWER);

        // Viewer may list and download history.
        mockMvc.perform(get("/api/files/" + fileId + "/versions")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));
        MvcResult result = mockMvc.perform(get("/api/files/" + fileId
                        + "/versions/" + version1.getId() + "/download")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(result)).isEqualTo("first");

        // Viewer must not be able to restore, and the attempt must not touch storage.
        org.mockito.Mockito.clearInvocations(storageService);
        mockMvc.perform(post("/api/files/" + fileId + "/versions/"
                        + version1.getId() + "/restore")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        assertThat(versionsOf(fileId)).hasSize(2);
        verify(storageService, never()).upload(any(), any(), anyLong(), anyString());
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void editorCanRestoreAnEarlierVersionCreatingANewLatestVersion() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String carol = registerAndGetToken("Carol", "carol@test.com");
        byte[] first = "alpha".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(alice, "doc.txt", "text/plain", first);
        FileVersion version1 = version(fileId, 1);
        replaceContent(alice, fileId, "doc.txt", "text/plain", "beta".getBytes())
                .andExpect(status().isOk());
        replaceContent(alice, fileId, "doc.txt", "text/plain", "gamma".getBytes())
                .andExpect(status().isOk());
        FileVersion version3 = version(fileId, 3);
        File beforeFile = reload(fileId);
        grantRole(beforeFile, "carol@test.com", FileRole.EDITOR);

        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));

        mockMvc.perform(post("/api/files/" + fileId + "/versions/"
                        + version1.getId() + "/restore")
                        .header("Authorization", "Bearer " + carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.currentVersion").value(4))
                .andExpect(jsonPath("$.sizeBytes").value(first.length))
                .andExpect(jsonPath("$.contentType").value("text/plain"));

        // A new version 4 is appended; history is not rewound.
        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(4);
        assertThat(versions).extracting(FileVersion::getVersionNumber)
                .containsExactly(1, 2, 3, 4);

        FileVersion restored = versions.get(3);
        assertThat(restored.getSizeBytes()).isEqualTo(first.length);
        assertThat(restored.getCreatedBy().getId())
                .isEqualTo(UUID.fromString(userId("carol@test.com")));

        // The file now points at a brand-new object holding the restored bytes.
        File afterFile = reload(fileId);
        String newKey = afterFile.getObjectKey();
        verify(storageService, times(1)).upload(
                eq(newKey), any(), eq((long) first.length), eq("text/plain"));
        assertThat(newKey).isEqualTo(restored.getObjectKey());
        assertThat(newKey).isNotEqualTo(version1.getObjectKey());
        assertThat(newKey).isNotEqualTo(version3.getObjectKey());

        // Identity, owner, folder, name and size are preserved/updated appropriately.
        assertThat(afterFile.getId()).isEqualTo(beforeFile.getId());
        assertThat(afterFile.getOwner().getId()).isEqualTo(beforeFile.getOwner().getId());
        assertThat(afterFile.getName()).isEqualTo("doc.txt");

        // Every historical object is left untouched.
        verify(storageService, never()).delete(version1.getObjectKey());
        verify(storageService, never()).delete(version3.getObjectKey());
    }

    @Test
    void restoredVersionRemainsDownloadableAndHistorySurvivesRestoration() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] first = "oldest".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(token, "log.txt", "text/plain", first);
        FileVersion version1 = version(fileId, 1);
        replaceContent(token, fileId, "log.txt", "text/plain", "newer".getBytes())
                .andExpect(status().isOk());

        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));
        mockMvc.perform(post("/api/files/" + fileId + "/versions/"
                        + version1.getId() + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Version 1 is still present after restoration.
        List<FileVersion> versions = versionsOf(fileId);
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).getId()).isEqualTo(version1.getId());

        // And it is still downloadable from its own key.
        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));
        MvcResult result = mockMvc.perform(get("/api/files/" + fileId
                        + "/versions/" + version1.getId() + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(result)).isEqualTo("oldest");
    }

    @Test
    void failedRestorationDoesNotCreateVersionAndCleansUpNewObject() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] first = "original".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(token, "atomic.txt", "text/plain", first);
        FileVersion version1 = version(fileId, 1);
        replaceContent(token, fileId, "atomic.txt", "text/plain", "current".getBytes())
                .andExpect(status().isOk());
        FileVersion version2 = version(fileId, 2);
        File before = reload(fileId);

        org.mockito.Mockito.when(storageService.download(version1.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(first));
        var databaseFailure =
                new org.springframework.dao.DataIntegrityViolationException("simulated");
        org.mockito.Mockito.doThrow(databaseFailure)
                .when(fileRepository).saveAndFlush(any(File.class));

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,
                () -> mockMvc.perform(post("/api/files/" + fileId + "/versions/"
                                + version1.getId() + "/restore")
                        .header("Authorization", "Bearer " + token)));

        // No new version was recorded and the current pointer is unchanged.
        assertThat(versionsOf(fileId)).hasSize(2);
        assertThat(reload(fileId).getObjectKey()).isEqualTo(before.getObjectKey());

        // Only the orphaned restore object is cleaned up; historical objects survive.
        verify(storageService, times(1)).delete(org.mockito.ArgumentMatchers.argThat(
                key -> !key.equals(version1.getObjectKey())
                        && !key.equals(version2.getObjectKey())
                        && key.contains("atomic.txt")));
        verify(storageService, never()).delete(version1.getObjectKey());
        verify(storageService, never()).delete(version2.getObjectKey());
    }

    @Test
    void currentUploadAndDownloadBehaviorStillWorks() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] latest = "latest-bytes".getBytes(StandardCharsets.UTF_8);
        String fileId = uploadAndGetId(token, "live.txt", "text/plain", "old".getBytes());
        replaceContent(token, fileId, "live.txt", "text/plain", latest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));

        FileVersion latestVersion = version(fileId, 2);
        org.mockito.Mockito.when(storageService.download(latestVersion.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(latest));

        MvcResult result = mockMvc.perform(get("/api/files/" + fileId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(result)).isEqualTo("latest-bytes");

        mockMvc.perform(get("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void metadataOnlyFileReportsCurrentVersionZero() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createMetadataAndGetId(token, "placeholder.txt", "text/plain", 5);

        mockMvc.perform(get("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(0));

        mockMvc.perform(get("/api/files/" + fileId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private String bodyOf(MvcResult result) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
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

    private String createMetadataAndGetId(
            String token, String name, String contentType, long sizeBytes) throws Exception {

        String body = mockMvc.perform(post("/api/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"contentType\":\""
                                + contentType + "\",\"sizeBytes\":" + sizeBytes + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private List<FileVersion> versionsOf(String fileId) {
        return fileVersionRepository.findByFileIdOrderByVersionNumberAsc(UUID.fromString(fileId));
    }

    private FileVersion version(String fileId, int versionNumber) {
        return fileVersionRepository
                .findByFileIdAndVersionNumber(UUID.fromString(fileId), versionNumber)
                .orElseThrow();
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
