package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.entity.File;
import com.minidrive.entity.ShareLink;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.ShareLinkRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.ShareTokenService;
import com.minidrive.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for Phase 6.3: owner-only share-link management plus
 * anonymous, read-only public access through a bearer token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShareLinkControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;
    @Autowired private ShareTokenService shareTokenService;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        shareLinkRepository.deleteAll();
        permissionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanCreateListAndRevokeShareLink() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.pdf", "application/pdf", 1024);

        String createBody = mockMvc.perform(createLink(alice, fileId, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.fileId").value(fileId))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String shareId = JsonPath.read(createBody, "$.id");
        String token = JsonPath.read(createBody, "$.token");

        mockMvc.perform(get("/api/files/" + fileId + "/share-links")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(shareId))
                .andExpect(jsonPath("$[0].fileId").value(fileId))
                .andExpect(jsonPath("$[0].expired").value(false))
                // Metadata never leaks the raw token or its stored hash.
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());

        // The raw token is genuinely usable before revocation.
        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("report.pdf"));

        mockMvc.perform(delete("/api/files/" + fileId + "/share-links/" + shareId)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/files/" + fileId + "/share-links")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/share/" + token)).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0, shareLinkRepository.count());
    }

    @Test
    void createReturnsTokenOnceWhileOnlyTheHashIsPersisted() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "notes.txt", "text/plain", 12);

        String first = mockMvc.perform(createLink(alice, fileId, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(createLink(alice, fileId, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String tokenOne = JsonPath.read(first, "$.token");
        String tokenTwo = JsonPath.read(second, "$.token");
        org.junit.jupiter.api.Assertions.assertNotEquals(tokenOne, tokenTwo);

        // The raw token is never stored, only a 64-char SHA-256 hash of it.
        org.junit.jupiter.api.Assertions.assertTrue(
                shareLinkRepository.findByTokenHash(tokenOne).isEmpty());
        ShareLink stored = shareLinkRepository
                .findByTokenHash(shareTokenService.hash(tokenOne)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals(tokenOne, stored.getTokenHash());
        org.junit.jupiter.api.Assertions.assertEquals(64, stored.getTokenHash().length());
    }

    @Test
    void anonymousCanReadAndDownloadWithoutJwt() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "share.txt", "text/plain", 5);
        String token = createLinkAndGetToken(alice, fileId, null);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(file.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("share.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(5))
                // Internal fields are never exposed to an anonymous visitor.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.checksum").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        MvcResult result = mockMvc.perform(get("/api/share/" + token + "/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain"))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                "hello", result.getResponse().getContentAsString());
        org.mockito.Mockito.verify(storageService).download(file.getObjectKey());
    }

    @Test
    void invalidTokenIsRejectedForAnonymousAccess() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        createFileAndGetId(alice, "share.txt", "text/plain", 5);

        mockMvc.perform(get("/api/share/not-a-real-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "This share link is invalid, expired, or has been revoked"));
        mockMvc.perform(get("/api/share/not-a-real-token/download"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .download(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void expiredLinkIsRejectedEvenThoughTheTokenExists() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "old.txt", "text/plain", 5);
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();

        String rawToken = "expired-bearer-token-value";
        shareLinkRepository.saveAndFlush(new ShareLink(
                file,
                shareTokenService.hash(rawToken),
                LocalDateTime.now().minusMinutes(1)));

        mockMvc.perform(get("/api/share/" + rawToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/share/" + rawToken + "/download"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .download(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void creatingLinkWithPastExpiryIsRejected() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);

        mockMvc.perform(createLink(alice, fileId,
                        "{\"expiresAt\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Expiration time must be in the future"));
        org.junit.jupiter.api.Assertions.assertEquals(0, shareLinkRepository.count());
    }

    @Test
    void futureExpiryIsPersistedAndReported() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);

        String body = mockMvc.perform(createLink(alice, fileId,
                        "{\"expiresAt\":\"2999-01-01T00:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value("2999-01-01T00:00:00"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");

        mockMvc.perform(get("/api/files/" + fileId + "/share-links")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expired").value(false));

        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("report.txt"));
    }

    @Test
    void nonOwnersCannotManageShareLinks() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "shared.txt", "text/plain", 5);
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String carol = registerAndGetToken("Carol", "carol@test.com");
        mockMvc.perform(grant(alice, fileId, "bob@test.com", "VIEWER")).andExpect(status().isOk());
        mockMvc.perform(grant(alice, fileId, "carol@test.com", "EDITOR")).andExpect(status().isOk());

        // A VIEWER cannot create, list or revoke links.
        mockMvc.perform(createLink(bob, fileId, null)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + fileId + "/share-links")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/files/" + fileId + "/share-links/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        // An EDITOR is likewise not the owner.
        mockMvc.perform(createLink(carol, fileId, null)).andExpect(status().isForbidden());

        // An unrelated user cannot even observe that the file exists.
        String dave = registerAndGetToken("Dave", "dave@test.com");
        mockMvc.perform(createLink(dave, fileId, null)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/" + fileId + "/share-links")
                        .header("Authorization", "Bearer " + dave))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/files/" + fileId + "/share-links/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + dave))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertEquals(0, shareLinkRepository.count());
    }

    @Test
    void unauthenticatedManagementIsUnauthorized() throws Exception {
        String fileId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/files/" + fileId + "/share-link"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId + "/share-links"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/files/" + fileId + "/share-links/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicShareAccessIsReadOnly() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "readonly.txt", "text/plain", 5);
        String token = createLinkAndGetToken(alice, fileId, null);

        mockMvc.perform(post("/api/share/" + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/share/" + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/share/" + token)).andExpect(status().isUnauthorized());

        // Nothing changed: the link still resolves and the file is untouched.
        mockMvc.perform(get("/api/share/" + token)).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(
                "readonly.txt",
                fileRepository.findById(UUID.fromString(fileId)).orElseThrow().getName());
    }

    @Test
    void linkForOneFileCannotAccessAnotherFile() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileA = createFileAndGetId(alice, "a.txt", "text/plain", 3);
        String fileB = createFileAndGetId(alice, "b.txt", "text/plain", 3);
        String tokenA = createLinkAndGetToken(alice, fileA, null);
        String tokenB = createLinkAndGetToken(alice, fileB, null);

        File entityA = fileRepository.findById(UUID.fromString(fileA)).orElseThrow();
        File entityB = fileRepository.findById(UUID.fromString(fileB)).orElseThrow();
        org.mockito.Mockito.when(storageService.download(entityA.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("AAA".getBytes()));
        org.mockito.Mockito.when(storageService.download(entityB.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("BBB".getBytes()));

        mockMvc.perform(get("/api/share/" + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("a.txt"));
        // Passing a different file id changes nothing: the token alone authorizes.
        mockMvc.perform(get("/api/share/" + tokenA).param("fileId", fileB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("a.txt"));

        MvcResult result = mockMvc.perform(get("/api/share/" + tokenA + "/download"))
                .andExpect(status().isOk())
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                "AAA", result.getResponse().getContentAsString());
        org.mockito.Mockito.verify(storageService).download(entityA.getObjectKey());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .download(entityB.getObjectKey());
    }

    @Test
    void existingProtectedEndpointsRemainProtected() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "secret.txt", "text/plain", 3);

        mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId + "/download"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId + "/permissions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/" + fileId + "/share-links"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidFileIdsAndMissingLinksAreHandled() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFileAndGetId(alice, "report.txt", "text/plain", 5);

        mockMvc.perform(createLink(alice, "not-a-uuid", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/files/" + fileId + "/share-links/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(createLink(alice, UUID.randomUUID().toString(), null))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder createLink(
            String token,
            String fileId,
            String body) {

        MockHttpServletRequestBuilder request = post("/api/files/" + fileId + "/share-link")
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    private String createLinkAndGetToken(
            String token,
            String fileId,
            String body) throws Exception {

        String response = mockMvc.perform(createLink(token, fileId, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
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
