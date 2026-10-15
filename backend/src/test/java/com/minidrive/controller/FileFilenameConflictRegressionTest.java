package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.entity.File;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduces the known filename-conflict failure mode against the real database
 * constraint rather than a canned exception: the availability pre-check is
 * forced to pass while the real {@code uq_files_owner_folder_name} unique index
 * raises the violation on insert/flush, exactly like a lost create/update race.
 *
 * <p>The File entity declaration mirrors the Flyway-only index so the
 * create-drop H2 test schema carries the real unique index; at runtime the
 * migration owns it and this declaration is inert.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileFilenameConflictRegressionTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @MockitoSpyBean private FileRepository fileRepository;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void lostCreateRaceAgainstUniqueIndexReturnsStandardBadRequestNot500() throws Exception {
        String token = registerAndGetToken("Alice", "alice-race@test.com");
        UUID aliceId = userRepository.findByEmail("alice-race@test.com").orElseThrow().getId();
        String folderId = createFolderAndGetId(token, "RaceFolder");

        // The row that wins the concurrent race is already in the table.
        File winner = new File(
                userRepository.findByEmail("alice-race@test.com").orElseThrow(),
                folderRepository.findById(UUID.fromString(folderId)).orElseThrow(),
                "conflict.txt", "text/plain", 1, null);
        winner.setObjectKey("files/" + aliceId + "/winner-object");
        fileRepository.saveAndFlush(winner);

        // The losing request's availability pre-check is raced away.
        org.mockito.Mockito.doReturn(false).when(fileRepository)
                .existsByOwnerIdAndFolderIdAndName(aliceId, UUID.fromString(folderId), "conflict.txt");

        // The loser's insert hits the real unique index; the response must be
        // the standard 400 duplicate-name error, not a 500.
        mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile(
                                "file", "conflict.txt", "text/plain", new byte[]{1}))
                        .param("folderId", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("A file with this name already exists in this folder"));

        // The loser was not persisted and the winner is untouched.
        org.junit.jupiter.api.Assertions.assertEquals(1, fileRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals("conflict.txt",
                fileRepository.findAll().iterator().next().getName());
    }

    @Test
    void renameRaceAgainstUniqueIndexReturnsStandardBadRequestNot500() throws Exception {
        String token = registerAndGetToken("Alice", "alice-rename@test.com");
        UUID aliceId = userRepository.findByEmail("alice-rename@test.com").orElseThrow().getId();
        String folderId = createFolderAndGetId(token, "RenameFolder");
        createMultipartAndGetOk(token, "first.txt", folderId);
        String fileId = createMultipartAndGetOk(token, "second.txt", folderId);

        // The rename pre-check is raced away: "first.txt" is exactly the row a
        // concurrent create put there, so renaming "second.txt" into that name
        // is rejected by the real unique index at flush.
        org.mockito.Mockito.doReturn(false).when(fileRepository)
                .existsByOwnerIdAndFolderIdAndName(aliceId, UUID.fromString(folderId), "first.txt");

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"first.txt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("A file with this name already exists in this folder"));

        // The rename was rolled back: both files keep their original names, no
        // storage object was touched, and no file was lost.
        File after = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("second.txt", after.getName());
        org.junit.jupiter.api.Assertions.assertEquals(2, fileRepository.count());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .delete(org.mockito.Mockito.anyString());
    }

    private MockMultipartHttpServletRequestBuilder upload(
            String token, String name, String folderId, long size) {

        MockMultipartHttpServletRequestBuilder request = multipart("/api/files")
                .file(new MockMultipartFile("file", name, "text/plain", new byte[(int) size]));
        request.param("folderId", folderId);
        request.header("Authorization", "Bearer " + token);
        return request;
    }

    private String createMultipartAndGetOk(String token, String name, String folderId) throws Exception {
        String body = mockMvc.perform(upload(token, name, folderId, 3))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createFolderAndGetId(String token, String name) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/folders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
        String body = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email
                        + "\",\"password\":\"" + PASSWORD + "\"}");
        return mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
    }
}
