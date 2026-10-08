package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FileRepository fileRepository;

    @BeforeEach
    void cleanup() {
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
    void deleteFileMetadataRemovesRecord() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(token, "temp.txt", null, "text/plain", 1);

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals(0, fileRepository.count());
        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessToAnotherUsersFileIsRejected() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFileId = createFileAndGetId(aliceToken, "secret.txt", null, "text/plain", 3);

        mockMvc.perform(get("/api/files/" + aliceFileId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/files/" + aliceFileId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijacked.txt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/files/" + aliceFileId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertEquals(1, fileRepository.count());
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
        mockMvc.perform(get("/api/files/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/files/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
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

        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\"");
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
