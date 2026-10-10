package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.entity.File;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @MockitoSpyBean private FileRepository fileRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        fileVersionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createFileMetadataReturnsCreatedWithPendingUploadStatus() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");

        mockMvc.perform(createFile(token, "report.pdf", null, "application/pdf", 1024))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(jsonPath("$.folderId").value(nullValue()))
                .andExpect(jsonPath("$.ownerId").value(aliceId))
                .andExpect(jsonPath("$.objectKey").isNotEmpty())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(1024))
                .andExpect(jsonPath("$.checksum").value(nullValue()))
                .andExpect(jsonPath("$.uploadStatus").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        var files = fileRepository.findByOwnerIdAndFolderIdIsNull(
                UUID.fromString(aliceId));
        org.junit.jupiter.api.Assertions.assertEquals(1, files.size());
        org.junit.jupiter.api.Assertions.assertEquals("report.pdf", files.get(0).getName());
    }

    @Test
    void listRootFilesReturnsOnlyRootFilesOfAuthenticatedUser() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents");
        createFileAndGetId(token, "root.txt", null, "text/plain", 10);
        createFileAndGetId(token, "nested.txt", folderId, "text/plain", 20);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("root.txt"))
                .andExpect(jsonPath("$[0].folderId").value(nullValue()));
    }

    @Test
    void listFilesIsIsolatedByAuthenticatedOwnerAndUsesCompactResponseDto() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        createFileAndGetId(aliceToken, "A1.txt", null, "text/plain", 11);
        createFileAndGetId(aliceToken, "A2.txt", null, "text/plain", 22);
        createFileAndGetId(bobToken, "B1.txt", null, "text/plain", 33);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("A1.txt"), org.hamcrest.Matchers.is("A2.txt"))))
                .andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.containsInAnyOrder("A1.txt", "A2.txt")))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("B1.txt"))))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].contentType").value("text/plain"))
                .andExpect(jsonPath("$[0].sizeBytes").isNumber())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$[0].ownerId").doesNotExist());

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("B1.txt"));

        org.mockito.Mockito.verify(fileRepository).findByOwnerIdAndFolderIdIsNull(
                UUID.fromString(userId("alice@test.com")));
        org.mockito.Mockito.verify(fileRepository).findByOwnerIdAndFolderIdIsNull(
                UUID.fromString(userId("bob@test.com")));
    }

    @Test
    void listFilesReturnsEmptyArrayWhenOwnerHasNoFiles() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listFilesByFolderUsesAuthenticatedOwnerScopedRepositoryQuery() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFolder = createFolderAndGetId(aliceToken, "Alice folder");
        String bobFolder = createFolderAndGetId(bobToken, "Bob folder");
        createFileAndGetId(aliceToken, "inside.txt", aliceFolder, "text/plain", 10);
        createFileAndGetId(aliceToken, "root.txt", null, "text/plain", 10);
        createFileAndGetId(bobToken, "private.txt", bobFolder, "text/plain", 10);

        mockMvc.perform(get("/api/files").param("folderId", aliceFolder)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("inside.txt"));

        org.mockito.Mockito.verify(fileRepository).findByOwnerIdAndFolderId(
                UUID.fromString(userId("alice@test.com")), UUID.fromString(aliceFolder));

        mockMvc.perform(get("/api/files").param("folderId", bobFolder)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listFilesInFolderReturnsOnlyDirectFiles() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents");
        createFileAndGetId(token, "inside.txt", folderId, "text/plain", 5);
        createFileAndGetId(token, "outside.txt", null, "text/plain", 5);

        mockMvc.perform(get("/api/folders/" + folderId + "/files")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("inside.txt"))
                .andExpect(jsonPath("$[0].folderId").value(folderId));
    }

    @Test
    void getFileMetadataReturnsOwnedFileById() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "notes.txt", null, "text/plain", 42);

        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.name").value("notes.txt"))
                .andExpect(jsonPath("$.sizeBytes").value(42));
    }

    @Test
    void updateFileMetadataChangesNameAndMovesFolder() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents");
        String fileId = createFileAndGetId(token, "draft.txt", null, "text/plain", 7);

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"final.txt\",\"folderId\":\"" + folderId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.name").value("final.txt"))
                .andExpect(jsonPath("$.folderId").value(folderId));

        var files = fileRepository.findByOwnerIdAndFolderId(
                UUID.fromString(userId("alice@test.com")), UUID.fromString(folderId));
        org.junit.jupiter.api.Assertions.assertEquals(1, files.size());
        org.junit.jupiter.api.Assertions.assertEquals("final.txt", files.get(0).getName());
    }

    @Test
    void deleteFileRemovesStorageObjectAndMetadata() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "temp.txt", null, "text/plain", 1);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(storageService).delete(file.getObjectKey());
        org.mockito.Mockito.verify(fileRepository).findByIdAndOwnerId(
                UUID.fromString(fileId), UUID.fromString(userId("alice@test.com")));
        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessToAnotherUsersFileIsRejected() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFileId = createFileAndGetId(aliceToken, "secret.txt", null, "text/plain", 3);
        String bobFileId = createFileAndGetId(bobToken, "B1.txt", null, "text/plain", 4);

        mockMvc.perform(get("/api/files/" + aliceFileId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/files/" + aliceFileId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijacked.txt\"}"))
                .andExpect(status().isForbidden());

        var aliceFile = fileRepository.findById(UUID.fromString(aliceFileId)).orElseThrow();
        var bobFile = fileRepository.findById(UUID.fromString(bobFileId)).orElseThrow();
        mockMvc.perform(delete("/api/files/" + aliceFileId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/files/" + bobFileId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertEquals(2, fileRepository.count());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never()).delete(aliceFile.getObjectKey());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never()).delete(bobFile.getObjectKey());
    }

    @Test
    void deleteMissingAndInvalidIdsAreHandledWithoutStorageCalls() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        mockMvc.perform(delete("/api/files/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/files/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void storageDeleteFailurePreservesMetadataAndReturnsControlledError() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "keep.txt", null, "text/plain", 1);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.mockito.Mockito.doThrow(new com.minidrive.exception.StorageException("private details"))
                .when(storageService).delete(file.getObjectKey());

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("File storage operation failed"));

        org.junit.jupiter.api.Assertions.assertTrue(fileRepository.findById(UUID.fromString(fileId)).isPresent());
        org.mockito.Mockito.verify(fileRepository, org.mockito.Mockito.never()).delete(file);
    }

    @Test
    void metadataDeleteFailureIsPropagatedAfterStorageDeletion() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "db-failure.txt", null, "text/plain", 1);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated"))
                .when(fileRepository).flush();

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,
                () -> mockMvc.perform(delete("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token)));

        org.mockito.Mockito.verify(storageService).delete(file.getObjectKey());
        org.junit.jupiter.api.Assertions.assertTrue(fileRepository.findById(UUID.fromString(fileId)).isPresent());
    }

    @Test
    void movingFileIntoAnotherUsersFolderIsForbidden() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFileId = createFileAndGetId(aliceToken, "mine.txt", null, "text/plain", 3);
        String bobFolderId = createFolderAndGetId(bobToken, "BobFolder");

        mockMvc.perform(patch("/api/files/" + aliceFileId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + bobFolderId + "\"}"))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertNull(
                fileRepository.findById(UUID.fromString(aliceFileId)).orElseThrow().getFolder());
    }

    @Test
    void invalidFolderReferenceIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(createFile(token, "lost.txt", UUID.randomUUID().toString(), "text/plain", 1))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/files/"
                        + createFileAndGetId(token, "keep.txt", null, "text/plain", 1))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertEquals(1, fileRepository.count());
    }

    @Test
    void duplicateFileNameInSameFolderIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        createFileAndGetId(token, "dup.txt", null, "text/plain", 1);

        mockMvc.perform(createFile(token, "dup.txt", null, "text/plain", 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("A file with this name already exists in this folder"));
    }

    @Test
    void sameFileNameInDifferentFoldersIsAllowed() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents");
        createFileAndGetId(token, "readme.md", null, "text/markdown", 1);

        mockMvc.perform(createFile(token, "readme.md", folderId, "text/markdown", 2))
                .andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedAccessIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/files/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedMultipartUploadPersistsMetadataForAuthenticatedOwner() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] bytes = "hello upload".getBytes();
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(jsonPath("$.ownerId").value(userId("alice@test.com")))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.objectKey").value(org.hamcrest.Matchers.not("report.pdf")));
        var saved = fileRepository.findByOwnerIdAndFolderIdIsNull(UUID.fromString(userId("alice@test.com")));
        org.junit.jupiter.api.Assertions.assertEquals(1, saved.size());
        org.junit.jupiter.api.Assertions.assertTrue(saved.get(0).getObjectKey().contains("report.pdf"));
        org.mockito.Mockito.verify(storageService).upload(
                org.mockito.ArgumentMatchers.contains("report.pdf"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq((long) bytes.length),
                org.mockito.ArgumentMatchers.eq("application/pdf"));
    }

    @Test
    void emptyMultipartFileIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
    }

    @Test
    void storageFailureDoesNotCreateMetadata() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        org.mockito.Mockito.doThrow(new com.minidrive.exception.StorageException("failed"))
                .when(storageService).upload(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", new byte[]{1}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway());
        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
    }

    @Test
    void downloadStreamsOwnedFileWithMetadataHeadersAndHidesOtherOwners() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String aliceId = createFileAndGetId(alice, "a\"report\r\n.txt", null, "text/plain", 5);
        String bobId = createFileAndGetId(bob, "B1.txt", null, "application/pdf", 4);
        var aFile = fileRepository.findById(UUID.fromString(aliceId)).orElseThrow();
        var bFile = fileRepository.findById(UUID.fromString(bobId)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(aFile.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        var response = mockMvc.perform(get("/api/files/" + aliceId + "/download")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("text/plain"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("a_report__.txt")))
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertEquals("hello", response.getContentAsString());
        org.mockito.Mockito.verify(storageService).download(aFile.getObjectKey());
        org.mockito.Mockito.verify(fileRepository).findByIdAndOwnerId(
                UUID.fromString(aliceId), UUID.fromString(userId("alice@test.com")));

        mockMvc.perform(get("/api/files/" + bobId + "/download")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never()).download(bFile.getObjectKey());
        org.mockito.Mockito.verify(fileRepository).findByIdAndOwnerId(
                UUID.fromString(bobId), UUID.fromString(userId("alice@test.com")));
    }

    @Test
    void downloadEncodesUnicodeFilenameInContentDisposition() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "résumé-雪.txt", null, "text/plain", 5);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(file.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        var result = mockMvc.perform(get("/api/files/" + fileId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
        }
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus());
        String disposition = result.getResponse().getHeader("Content-Disposition");

        org.junit.jupiter.api.Assertions.assertNotNull(disposition);
        org.junit.jupiter.api.Assertions.assertTrue(disposition.contains("filename*=UTF-8''"), disposition);
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\r"));
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\n"));
    }

    @Test
    void downloadRejectsUnauthenticatedInvalidAndMissingIdsAndControlsStorageFailure() throws Exception {
        mockMvc.perform(get("/api/files/" + UUID.randomUUID() + "/download"))
                .andExpect(status().isUnauthorized());
        String token = registerAndGetToken("Alice", "alice@test.com");
        mockMvc.perform(get("/api/files/not-a-uuid/download").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/files/" + UUID.randomUUID() + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        String id = createFileAndGetId(token, "missing.txt", null, "text/plain", 3);
        var file = fileRepository.findById(UUID.fromString(id)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(file.getObjectKey()))
                .thenThrow(new com.minidrive.exception.StorageException("private storage details"));
        mockMvc.perform(get("/api/files/" + id + "/download").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("File storage operation failed"));
    }

    @Test
    void metadataPersistenceFailureAttemptsObjectCleanup() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        var databaseFailure = new org.springframework.dao.DataIntegrityViolationException("simulated");
        var cleanupFailure = new com.minidrive.exception.StorageException("cleanup failed");
        org.mockito.Mockito.doThrow(databaseFailure)
                .when(fileRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(File.class));
        org.mockito.Mockito.doThrow(cleanupFailure).when(storageService)
                .delete(org.mockito.ArgumentMatchers.anyString());
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,
                () -> mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", new byte[]{1}))
                        .header("Authorization", "Bearer " + token)));
        Throwable cause = thrown;
        while (cause != null && cause != databaseFailure) cause = cause.getCause();
        org.junit.jupiter.api.Assertions.assertSame(databaseFailure, cause);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[]{cleanupFailure},
                databaseFailure.getSuppressed());
        org.mockito.Mockito.verify(storageService).delete(org.mockito.ArgumentMatchers.contains("x.txt"));
        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
    }

    @Test
    void invalidMetadataIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(createFile(token, "", null, "text/plain", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());

        mockMvc.perform(createFile(token, "bad.txt", null, "text/plain", -5))
                .andExpect(status().isBadRequest());

        mockMvc.perform(createFile(token, "../escape", null, "text/plain", 1))
                .andExpect(status().isBadRequest());

        mockMvc.perform(createFile(token, "badtype.txt", null, "not-a-content-type", 1))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
    }

    private MockHttpServletRequestBuilder createFile(
            String token,
            String name,
            String folderId,
            String contentType,
            long sizeBytes) {

        String safeName = name.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        StringBuilder body = new StringBuilder("{\"name\":\"" + safeName + "\"");
        if (folderId != null) {
            body.append(",\"folderId\":\"").append(folderId).append("\"");
        }
        body.append(",\"contentType\":\"").append(contentType).append("\"");
        body.append(",\"sizeBytes\":").append(sizeBytes);
        body.append("}");

        return post("/api/files")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private String createFileAndGetId(
            String token,
            String name,
            String folderId,
            String contentType,
            long sizeBytes) throws Exception {

        String body = mockMvc.perform(createFile(token, name, folderId, contentType, sizeBytes))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private String createFolderAndGetId(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/folders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
    }

    private String userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId().toString();
    }
}
