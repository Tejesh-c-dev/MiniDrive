package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.dto.upload.ChunkUploadResponse;
import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.UploadSessionService;
import com.minidrive.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.2: persistent upload sessions and chunk ingestion. Covers session
 * creation/validation, ownership scoping, chunk ingestion and the resume map,
 * size/index validation, retries, concurrency, abort/cleanup, and storage and
 * database failures. {@link StorageService} is mocked so no real MinIO or
 * PostgreSQL instance is required.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadControllerTest {

    private static final String PASSWORD = "password123";
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024 * 1024;

    @Autowired private MockMvc mockMvc;
    @Autowired private UploadSessionService uploadSessionService;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UploadSessionRepository uploadSessionRepository;
    @MockitoSpyBean private UploadChunkRepository uploadChunkRepository;
    @MockitoBean private StorageService storageService;

    @BeforeEach
    void cleanup() {
        uploadChunkRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    // -------------------------------------------------------------------
    // Session creation and validation
    // -------------------------------------------------------------------

    @Test
    void createsSessionWithServerSelectedChunkSizeAndCount() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        // Two full chunks plus a 10-byte remainder => three chunks.
        long size = 2L * CHUNK_SIZE + 10;

        String body = mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("movie.bin", "application/octet-stream", size)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkSizeBytes").value(CHUNK_SIZE))
                .andExpect(jsonPath("$.totalChunks").value(3))
                .andExpect(jsonPath("$.totalSizeBytes").value((int) size))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.filename").value("movie.bin"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String sessionId = JsonPath.read(body, "$.id");
        UploadSession session = uploadSessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        assertThat(session.getOwner().getId()).isEqualTo(UUID.fromString(userId("alice@test.com")));
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    @Test
    void zeroByteUploadGetsExactlyOneEmptyChunk() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        String sessionId = createSession(token, "empty.txt", "text/plain", 0);

        mockMvc.perform(get("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(1))
                .andExpect(jsonPath("$.uploadedChunks").isEmpty());

        // The single empty chunk is accepted and makes the file complete.
        putChunk(token, sessionId, 0, new byte[0])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(0));

        mockMvc.perform(get("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.uploadedChunks[0]").value(0));
    }

    @Test
    void rejectsDeclaredSizeAboveLimit() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("huge.bin", "application/octet-stream", MAX_FILE_SIZE + 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidMetadata() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        // Negative size fails bean validation.
        mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("bad.txt", "text/plain", -1)))
                .andExpect(status().isBadRequest());

        // Blank filename fails bean validation.
        mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"  \",\"contentType\":\"text/plain\",\"totalSizeBytes\":1}"))
                .andExpect(status().isBadRequest());

        // Path separator in the filename is rejected by the service.
        mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("../escape.txt", "text/plain", 1)))
                .andExpect(status().isBadRequest());

        assertThat(uploadSessionRepository.count()).isZero();
    }

    // -------------------------------------------------------------------
    // Ownership enforcement and missing sessions
    // -------------------------------------------------------------------

    @Test
    void missingSessionIsNotFound() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");

        mockMvc.perform(get("/api/uploads/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotReadUploadOrAbortTheSession() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String sessionId = createSession(alice, "private.bin", "application/octet-stream", 100);

        mockMvc.perform(get("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());

        putChunk(bob, sessionId, 0, new byte[100])
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());

        // Alice's session is untouched and still has no chunks.
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
        assertThat(uploadSessionRepository.findById(UUID.fromString(sessionId))
                .orElseThrow().getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(post("/api/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("x.txt", "text/plain", 1)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/uploads/" + sessionId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/uploads/" + sessionId + "/chunks/0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[]{1}))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/uploads/" + sessionId))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------
    // Chunk ingestion and the resume map
    // -------------------------------------------------------------------

    @Test
    void ingestsChunksOutOfOrderAndReturnsSortedResumeMap() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = 2L * CHUNK_SIZE + 10;
        String sessionId = createSession(token, "movie.bin", "application/octet-stream", size);
        String ownerId = userId("alice@test.com");

        byte[] chunk0 = freshBytes(CHUNK_SIZE);
        byte[] chunk1 = freshBytes(CHUNK_SIZE);
        byte[] chunk2 = freshBytes(10);

        putChunk(token, sessionId, 1, chunk1).andExpect(status().isOk());
        putChunk(token, sessionId, 2, chunk2).andExpect(status().isOk());
        putChunk(token, sessionId, 0, chunk0).andExpect(status().isOk());

        mockMvc.perform(get("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.uploadedChunks.length()").value(3))
                .andExpect(jsonPath("$.uploadedChunks[0]").value(0))
                .andExpect(jsonPath("$.uploadedChunks[1]").value(1))
                .andExpect(jsonPath("$.uploadedChunks[2]").value(2));

        // Staging keys are server-generated under the reserved prefix and never
        // appear in the response body.
        List<UploadChunk> chunks = uploadChunkRepository
                .findBySessionIdOrderByChunkIndexAsc(UUID.fromString(sessionId));
        assertThat(chunks).extracting(UploadChunk::getChunkIndex).containsExactly(0, 1, 2);
        for (UploadChunk chunk : chunks) {
            assertThat(chunk.getStagingObjectKey()).startsWith(
                    "_uploads/" + ownerId + "/" + sessionId + "/");
            assertThat(chunk.getChecksum()).hasSize(64);
        }

        org.mockito.Mockito.verify(storageService).upload(
                eq("_uploads/" + ownerId + "/" + sessionId + "/0"),
                any(InputStream.class), eq((long) CHUNK_SIZE),
                eq("application/octet-stream"));
    }

    @Test
    void rejectsOutOfRangeChunkIndex() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = 2L * CHUNK_SIZE + 10; // 3 chunks: indexes 0..2
        String sessionId = createSession(token, "movie.bin", "application/octet-stream", size);

        putChunk(token, sessionId, 3, freshBytes(9)).andExpect(status().isBadRequest());
        putChunk(token, sessionId, -1, freshBytes(9)).andExpect(status().isBadRequest());

        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .upload(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void rejectsIncorrectChunkSize() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = 2L * CHUNK_SIZE + 10;
        String sessionId = createSession(token, "movie.bin", "application/octet-stream", size);

        // Too small for a full first chunk.
        putChunk(token, sessionId, 0, freshBytes(CHUNK_SIZE - 1))
                .andExpect(status().isBadRequest());

        // Too large for the final remainder chunk.
        putChunk(token, sessionId, 2, freshBytes(11))
                .andExpect(status().isBadRequest());

        // The final chunk's exact remainder is accepted.
        putChunk(token, sessionId, 2, freshBytes(10)).andExpect(status().isOk());

        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId)))
                .extracting(UploadChunk::getChunkIndex)
                .containsExactly(2);
    }

    // -------------------------------------------------------------------
    // Duplicate requests and retry behavior
    // -------------------------------------------------------------------

    @Test
    void duplicateChunkUploadIsIdempotent() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "file.bin", "application/octet-stream", 100);
        byte[] bytes = freshBytes(100);

        putChunk(token, sessionId, 0, bytes)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        putChunk(token, sessionId, 0, bytes)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).hasSize(1);
    }

    // -------------------------------------------------------------------
    // Concurrency
    // -------------------------------------------------------------------

    @Test
    void concurrentDistinctChunksAllPersist() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = 2L * CHUNK_SIZE; // exactly two chunks
        String sessionId = createSession(token, "pair.bin", "application/octet-stream", size);
        Authentication auth = auth("alice@test.com");

        byte[] first = freshBytes(CHUNK_SIZE);
        byte[] second = freshBytes(CHUNK_SIZE);

        List<Object> results = runConcurrently(
                () -> uploadSessionService.ingestChunk(
                        auth, UUID.fromString(sessionId), 0, new ByteArrayInputStream(first)),
                () -> uploadSessionService.ingestChunk(
                        auth, UUID.fromString(sessionId), 1, new ByteArrayInputStream(second)));

        assertThat(failures(results)).isEmpty();
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId)))
                .extracting(UploadChunk::getChunkIndex)
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void concurrentSameIndexUploadsConvergeToOneChunk() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "race.bin", "application/octet-stream", 100);
        Authentication auth = auth("alice@test.com");
        byte[] bytes = freshBytes(100);

        List<Object> results = runConcurrently(
                () -> uploadSessionService.ingestChunk(
                        auth, UUID.fromString(sessionId), 0, new ByteArrayInputStream(bytes)),
                () -> uploadSessionService.ingestChunk(
                        auth, UUID.fromString(sessionId), 0, new ByteArrayInputStream(bytes)));

        // Both attempts succeed; the unique (session_id, chunk_index) constraint
        // means exactly one row survives and exactly one attempt was a fresh write.
        assertThat(failures(results)).isEmpty();
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).hasSize(1);
        assertThat(results).allMatch(ChunkUploadResponse.class::isInstance);
        long freshWrites = results.stream()
                .map(ChunkUploadResponse.class::cast)
                .filter(response -> !response.isDuplicate())
                .count();
        assertThat(freshWrites).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // Abort and staging cleanup
    // -------------------------------------------------------------------

    @Test
    void abortRemovesStagingObjectsAndIsIdempotent() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = 2L * CHUNK_SIZE + 10;
        String sessionId = createSession(token, "cancel.bin", "application/octet-stream", size);

        putChunk(token, sessionId, 0, freshBytes(CHUNK_SIZE)).andExpect(status().isOk());
        putChunk(token, sessionId, 1, freshBytes(CHUNK_SIZE)).andExpect(status().isOk());

        String ownerId = userId("alice@test.com");
        mockMvc.perform(delete("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(storageService)
                .delete("_uploads/" + ownerId + "/" + sessionId + "/0");
        org.mockito.Mockito.verify(storageService)
                .delete("_uploads/" + ownerId + "/" + sessionId + "/1");

        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
        UploadSession session = uploadSessionRepository.findById(UUID.fromString(sessionId))
                .orElseThrow();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.ABORTED);

        // Aborting again is a no-op.
        mockMvc.perform(delete("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void abortedSessionRejectsFurtherChunks() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "cancel.bin", "application/octet-stream", 100);

        mockMvc.perform(delete("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        putChunk(token, sessionId, 0, freshBytes(100)).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------
    // Storage and database failures
    // -------------------------------------------------------------------

    @Test
    void storageFailureDuringChunkUploadDoesNotPersistChunk() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "file.bin", "application/octet-stream", 100);

        org.mockito.Mockito.doThrow(new com.minidrive.exception.StorageException("down"))
                .when(storageService).upload(anyString(), any(InputStream.class), anyLong(), anyString());

        putChunk(token, sessionId, 0, freshBytes(100)).andExpect(status().isBadGateway());

        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
    }

    @Test
    void databaseFailureDuringChunkPersistenceCleansUpStagingObject() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "atomic.bin", "application/octet-stream", 100);
        String ownerId = userId("alice@test.com");

        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("simulated"))
                .when(uploadChunkRepository).saveAndFlush(any(UploadChunk.class));

        assertThatThrownBy(() -> putChunk(token, sessionId, 0, freshBytes(100)))
                .isInstanceOf(jakarta.servlet.ServletException.class);

        String stagingKey = "_uploads/" + ownerId + "/" + sessionId + "/0";
        org.mockito.Mockito.verify(storageService).delete(stagingKey);
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, null);
    }

    /**
     * Runs the tasks on separate threads that all start together, returning each
     * result or the throwable it failed with (never throwing from {@code get}).
     */
    private List<Object> runConcurrently(Callable<?>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return (Object) task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        executor.shutdownNow();
        return results;
    }

    private List<Throwable> failures(List<Object> results) {
        return results.stream()
                .filter(Throwable.class::isInstance)
                .map(Throwable.class::cast)
                .toList();
    }

    private byte[] freshBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private String sessionJson(String filename, String contentType, long size) {
        return "{\"filename\":\"" + filename + "\",\"contentType\":\"" + contentType
                + "\",\"totalSizeBytes\":" + size + "}";
    }

    private String createSession(
            String token, String filename, String contentType, long size) throws Exception {
        String body = mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(filename, contentType, size)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions putChunk(String token, String sessionId, int index, byte[] bytes)
            throws Exception {
        return mockMvc.perform(put("/api/uploads/" + sessionId + "/chunks/" + index)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(bytes));
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
