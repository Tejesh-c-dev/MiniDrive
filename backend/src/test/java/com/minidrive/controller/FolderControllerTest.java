package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
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
class FolderControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;

    @BeforeEach
    void cleanup() {
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createRootFolderReturnsCreatedWithMetadata() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");

        mockMvc.perform(createFolder(token, "Documents", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Documents"))
                .andExpect(jsonPath("$.parentFolderId").value(nullValue()))
                .andExpect(jsonPath("$.ownerId").value(aliceId))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        var roots = folderRepository.findByOwnerIdAndParentFolderIdIsNull(
                UUID.fromString(aliceId));
        org.junit.jupiter.api.Assertions.assertEquals(1, roots.size());
        org.junit.jupiter.api.Assertions.assertEquals("Documents", roots.get(0).getName());
    }

    @Test
    void createNestedFolderLinksToOwnedParent() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootId = createFolderAndGetId(token, "Documents", null);

        mockMvc.perform(createFolder(token, "Projects", UUID.fromString(rootId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Projects"))
                .andExpect(jsonPath("$.parentFolderId").value(rootId));
    }

    @Test
    void listRootFoldersReturnsOnlyRootFoldersOfAuthenticatedUser() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootId = createFolderAndGetId(token, "Documents", null);
        createFolderAndGetId(token, "Photos", null);
        createFolderAndGetId(token, "Nested", UUID.fromString(rootId));

        mockMvc.perform(get("/api/folders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.containsInAnyOrder("Documents", "Photos")));
    }

    @Test
    void listChildrenReturnsOnlyDirectChildren() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootId = createFolderAndGetId(token, "Documents", null);
        createFolderAndGetId(token, "Projects", UUID.fromString(rootId));
        createFolderAndGetId(token, "Stray", null);

        mockMvc.perform(get("/api/folders/" + rootId + "/children").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Projects"))
                .andExpect(jsonPath("$[0].parentFolderId").value(rootId));
    }

    @Test
    void getFolderReturnsOwnedFolderById() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents", null);

        mockMvc.perform(get("/api/folders/" + folderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folderId))
                .andExpect(jsonPath("$.name").value("Documents"));
    }

    @Test
    void renameFolderUpdatesName() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolderAndGetId(token, "Documents", null);

        mockMvc.perform(patch("/api/folders/" + folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folderId))
                .andExpect(jsonPath("$.name").value("Renamed"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "Renamed",
                folderRepository.findById(UUID.fromString(folderId)).orElseThrow().getName());
    }

    @Test
    void deleteFolderRemovesFolderAndCascadesToChildren() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootId = createFolderAndGetId(token, "Documents", null);
        String childId = createFolderAndGetId(token, "Projects", UUID.fromString(rootId));

        mockMvc.perform(delete("/api/folders/" + rootId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals(0, folderRepository.count());
        mockMvc.perform(get("/api/folders/" + childId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateFolderNameWithinSameParentIsRejected() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootId = createFolderAndGetId(token, "Documents", null);

        mockMvc.perform(createFolder(token, "Documents", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A folder with this name already exists in this location"));

        String projectId = createFolderAndGetId(token, "Projects", UUID.fromString(rootId));

        mockMvc.perform(createFolder(token, "Projects", UUID.fromString(rootId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A folder with this name already exists in this location"));

        org.junit.jupiter.api.Assertions.assertTrue(folderRepository.findById(UUID.fromString(projectId)).isPresent());
    }

    @Test
    void folderWithSameNameInDifferentParentsIsAllowed() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String rootAId = createFolderAndGetId(token, "RootA", null);
        String rootBId = createFolderAndGetId(token, "RootB", null);

        mockMvc.perform(createFolder(token, "Projects", UUID.fromString(rootAId)))
                .andExpect(status().isCreated());
        mockMvc.perform(createFolder(token, "Projects", UUID.fromString(rootBId)))
                .andExpect(status().isCreated());
    }

    @Test
    void creatingFolderInsideAnotherUsersParentIsForbidden() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceRootId = createFolderAndGetId(aliceToken, "Documents", null);

        mockMvc.perform(createFolder(bobToken, "Sneaky", UUID.fromString(aliceRootId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("You do not have access to this folder"));
    }

    @Test
    void accessToAnotherUsersFolderIsRejected() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFolderId = createFolderAndGetId(aliceToken, "Documents", null);

        mockMvc.perform(get("/api/folders/" + aliceFolderId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/folders/" + aliceFolderId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/folders/" + aliceFolderId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertEquals(1, folderRepository.count());
    }

    @Test
    void unauthenticatedAccessIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/folders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Documents\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/folders/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void blankNameIsRejectedWithValidationErrors() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(createFolder(token, "", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());

        mockMvc.perform(createFolder(token, "   ", null))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, folderRepository.count());
    }

    private MockHttpServletRequestBuilder createFolder(String token, String name, UUID parentFolderId) {
        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\"");
        if (parentFolderId != null) {
            body.append(",\"parentFolderId\":\"").append(parentFolderId).append("\"");
        }
        body.append("}");

        return post("/api/folders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private String createFolderAndGetId(String token, String name, UUID parentFolderId) throws Exception {
        String body = mockMvc.perform(createFolder(token, name, parentFolderId))
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
