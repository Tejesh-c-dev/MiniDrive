package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.File;
import com.minidrive.entity.Folder;
import com.minidrive.entity.Permission;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SharedFilesControllerTest {
    private static final String PASSWORD = "password123";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired FileRepository files;
    @Autowired FolderRepository folders;
    @Autowired PermissionRepository permissions;
    @MockitoBean StorageService storage;

    @BeforeEach void clear() {
        permissions.deleteAll(); files.deleteAll(); folders.deleteAll(); users.deleteAll();
    }

    @Test void listsOnlyDirectCurrentPermissionsAndKeepsViewerReadOnly() throws Exception {
        String owner = register("Owner", "owner@md.test");
        String viewer = register("Viewer", "viewer@md.test");
        String stranger = register("Stranger", "stranger@md.test");
        String sharedId = create(owner, "shared.txt");
        String privateId = create(owner, "private.txt");
        permissions.saveAndFlush(new Permission(files.findById(UUID.fromString(sharedId)).orElseThrow(),
                users.findByEmail("viewer@md.test").orElseThrow(), FileRole.VIEWER));

        mvc.perform(get("/api/files/shared-with-me").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(sharedId))
                .andExpect(jsonPath("$[0].ownerName").value("Owner"))
                .andExpect(jsonPath("$[0].role").value("VIEWER"))
                .andExpect(jsonPath("$[0].name").value("shared.txt"))
                .andExpect(jsonPath("$[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$[0].checksum").doesNotExist())
                .andExpect(jsonPath("$[0].ownerEmail").doesNotExist());
        mvc.perform(get("/api/files/shared-with-me").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/files/shared-with-me"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/files/" + sharedId).header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"changed.txt\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/files/" + sharedId).header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/files/" + privateId + "/download").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isNotFound());
        verify(storage, never()).download(org.mockito.ArgumentMatchers.anyString());

        mvc.perform(delete("/api/files/" + sharedId + "/permissions/" + users.findByEmail("viewer@md.test").orElseThrow().getId())
                .header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/files/shared-with-me").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertEquals(0, permissions.count());
    }

    @Test void omitsSharedFilesInsideAFolderAlreadyBeingDeleted() throws Exception {
        register("Owner", "owner@md.test");
        String viewerToken = register("Viewer", "viewer@md.test");
        var owner = users.findByEmail("owner@md.test").orElseThrow();
        var viewer = users.findByEmail("viewer@md.test").orElseThrow();
        Folder folder = folders.saveAndFlush(new Folder(owner, null, "pending-delete"));
        File file = new File(owner, folder, "pending.txt", "text/plain", 3, null);
        file.setObjectKey("internal-storage-key");
        file = files.saveAndFlush(file);
        permissions.saveAndFlush(new Permission(file, viewer, FileRole.EDITOR));
        folder.markDeleting();
        folders.saveAndFlush(folder);

        mvc.perform(get("/api/files/shared-with-me").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertTrue(files.existsById(file.getId()));
    }

    private String create(String token, String name) throws Exception {
        String body = mvc.perform(post("/api/files").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name
                        + "\",\"contentType\":\"text/plain\",\"sizeBytes\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String register(String name, String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email
                        + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
