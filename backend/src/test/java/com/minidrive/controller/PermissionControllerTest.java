package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.entity.File;
import com.minidrive.repository.FileRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PermissionControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private PermissionRepository permissionRepository;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        permissionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanGrantListAndRevokePermission() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.pdf", "application/pdf", 1024);
        registerAndGetToken("Bob", "bob@test.com");
        String bobId = userId("bob@test.com");

        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bobId))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value(bobId));

        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + bobId)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertEquals(0, permissionRepository.count());
    }

    @Test
    void existingUsersCanReceiveViewerAndEditorAccess() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "shared.txt", "text/plain", 12);
        registerAndGetToken("Bob", "bob@test.com");
        registerAndGetToken("Carol", "carol@test.com");

        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        mockMvc.perform(grant(alice, fileId, "carol@test.com", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EDITOR"));

        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].role",
                        org.hamcrest.Matchers.containsInAnyOrder("VIEWER", "EDITOR")));
    }

    @Test
    void resharingUpdatesRoleWithoutCreatingDuplicates() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "shared.txt", "text/plain", 12);
        registerAndGetToken("Bob", "bob@test.com");
        String bobId = userId("bob@test.com");

        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER"))
                .andExpect(status().isOk());
        mockMvc.perform(grant(alice, fileId, "bob@test.com", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bobId))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].role").value("EDITOR"));
        org.junit.jupiter.api.Assertions.assertEquals(1, permissionRepository.count());
    }

    @Test
    void viewerCanReadAndDownloadButCannotEditDeleteOrShare() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "viewer.txt", "text/plain", 5);
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String bobId = userId("bob@test.com");
        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER")).andExpect(status().isOk());

        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(file.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("viewer.txt"));

        mockMvc.perform(get("/api/files/" + fileId + "/download").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hacked.txt\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        mockMvc.perform(grant(bob, fileId, "carol@test.com", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + bobId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertEquals(
                "viewer.txt", fileRepository.findById(UUID.fromString(fileId)).orElseThrow().getName());
        org.junit.jupiter.api.Assertions.assertEquals(1, permissionRepository.count());
    }

    @Test
    void editorCanRenameButCannotDeleteMoveOrManagePermissions() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "editor.txt", "text/plain", 5);
        String aliceFolder = createFolderAndGetId(alice, "AliceFolder");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        mockMvc.perform(grant(alice, fileId, "bob@test.com", "EDITOR")).andExpect(status().isOk());

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed.txt"));

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + aliceFolder + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        mockMvc.perform(grant(bob, fileId, "carol@test.com", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        File persisted = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("renamed.txt", persisted.getName());
        org.junit.jupiter.api.Assertions.assertNull(persisted.getFolder());
        org.junit.jupiter.api.Assertions.assertEquals(1, permissionRepository.count());
    }

    @Test
    void unrelatedUsersCannotAccessOrManageAnotherUsersFile() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "private.txt", "text/plain", 5);
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String bobId = userId("bob@test.com");

        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + fileId + "/download").header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijacked.txt\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(grant(bob, fileId, "bob@test.com", "VIEWER"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + bobId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertEquals(
                "private.txt", fileRepository.findById(UUID.fromString(fileId)).orElseThrow().getName());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .download(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void grantedAccessIsScopedToTheSingleFile() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String sharedFileId = createFileAndGetId(alice, "shared.txt", "text/plain", 5);
        String otherFileId = createFileAndGetId(alice, "other.txt", "text/plain", 5);
        String bob = registerAndGetToken("Bob", "bob@test.com");
        mockMvc.perform(grant(alice, sharedFileId, "bob@test.com", "VIEWER")).andExpect(status().isOk());

        mockMvc.perform(get("/api/files/" + sharedFileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/files/" + otherFileId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRecipientsAndInvalidRolesAreRejected() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);

        mockMvc.perform(grant(alice, fileId, "nobody@test.com", "VIEWER"))
                .andExpect(status().isNotFound());

        mockMvc.perform(grant(alice, fileId, "alice2@test.com", "OWNER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Role must be either VIEWER or EDITOR"));

        mockMvc.perform(post("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@test.com\",\"role\":\"SUPERADMIN\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isNotEmpty());

        org.junit.jupiter.api.Assertions.assertEquals(0, permissionRepository.count());
    }

    @Test
    void ownerCannotBeRevokedAndOwnerRoleCannotBeGranted() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);
        String aliceId = userId("alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String bobId = userId("bob@test.com");
        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + aliceId)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The file owner's access cannot be revoked"));

        mockMvc.perform(grant(alice, fileId, "alice@test.com", "VIEWER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The file owner already has full access to this file"));

        // A collaborator cannot escalate themselves by granting the OWNER role.
        mockMvc.perform(grant(bob, fileId, "bob@test.com", "OWNER"))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertEquals(1, permissionRepository.count());
        org.junit.jupiter.api.Assertions.assertTrue(
                permissionRepository.existsByFileIdAndUserId(
                        UUID.fromString(fileId), UUID.fromString(bobId)));
    }

    @Test
    void unauthenticatedAccessIsUnauthorized() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);

        mockMvc.perform(get("/api/files/" + fileId + "/permissions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/files/" + fileId + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@test.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingFileIsNotFoundForOwnerOperations() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String missing = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/files/" + missing + "/permissions")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(grant(alice, missing, "bob@test.com", "VIEWER"))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder grant(
            String token,
            String fileId,
            String email,
            String role) {

        return post("/api/files/" + fileId + "/permissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}");
    }

    private MockHttpServletRequestBuilder createFile(
            String token,
            String name,
            String contentType,
            long sizeBytes) {

        return post("/api/files")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"contentType\":\"" + contentType
                        + "\",\"sizeBytes\":" + sizeBytes + "}");
    }

    private String createFileAndGetId(
            String token,
            String name,
            String contentType,
            long sizeBytes) throws Exception {

        String body = mockMvc.perform(createFile(token, name, contentType, sizeBytes))
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
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"token\":\"")[1].split("\"")[0];
    }

    private String userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId().toString();
    }
}
