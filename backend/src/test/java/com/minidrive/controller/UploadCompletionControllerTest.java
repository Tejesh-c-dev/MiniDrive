package com.minidrive.controller;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.FileRole;
import com.minidrive.entity.FileVersion;
import com.minidrive.entity.Permission;
import com.minidrive.entity.UploadChunk;
import com.minidrive.entity.UploadSession;
import com.minidrive.entity.UploadSessionStatus;
import com.minidrive.entity.User;
import com.minidrive.exception.StorageException;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UploadChunkRepository;
import com.minidrive.repository.UploadSessionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.UploadSessionService;
import com.minidrive.storage.StorageService;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.4: chunk assembly and file finalization. Covers byte-for-byte
 * assembly in chunk-index order, completeness/size validation, zero-byte files,
 * new-file and replacement persistence, idempotent repeats, concurrent
 * completion protection, storage/database failure compensation, staging
 * cleanup, and expiry/authorization enforcement.
 *
 * <p>{@link StorageService} is mocked with an in-memory object store so the
 * whole flow (staging write, download during assembly, final write) is exercised
 * without a real MinIO or PostgreSQL instance. This is not a real
 * MinIO/PostgreSQL verification.</p>
 */
@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,prometheus")
@AutoConfigureMockMvc
class UploadCompletionControllerTest {

    private static final String PASSWORD = "password123";
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;

    @Autowired private MockMvc mockMvc;
    @Autowired private UploadSessionService uploadSessionService;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private UploadSessionRepository uploadSessionRepository;
    @Autowired private UploadChunkRepository uploadChunkRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoSpyBean private FileRepository fileRepository;
    @MockitoBean private StorageService storageService;

    /** In-memory stand-in for the object store used to verify assembly bytes. */
    private final ConcurrentHashMap<String, byte[]> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        stored.clear();
        uploadChunkRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        fileVersionRepository.deleteAll();
        permissionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();

        org.mockito.Mockito.when(storageService.download(anyString())).thenAnswer(invocation -> {
            byte[] data = stored.get(invocation.getArgument(0));
            if (data == null) {
                throw new StorageException("no such object: " + invocation.getArgument(0));
            }
            return new ByteArrayInputStream(data);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), readAll(invocation.getArgument(1)));
            return null;
        }).when(storageService).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.remove((String) invocation.getArgument(0));
            return null;
        }).when(storageService).delete(anyString());
    }

    // -------------------------------------------------------------------
    // Assembly correctness
    // -------------------------------------------------------------------

    @Test
    void assemblesChunksInIndexOrderIntoANewFile() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        // A full chunk plus a small remainder, uploaded out of order.
        long size = CHUNK_SIZE + 7L;
        String sessionId = createSession(token, "movie.bin", "application/octet-stream", size);

        byte[] chunk0 = patternBytes(CHUNK_SIZE, 0);
        byte[] chunk1 = patternBytes(7, 200);

        putChunk(token, sessionId, 1, chunk1).andExpect(status().isOk());
        putChunk(token, sessionId, 0, chunk0).andExpect(status().isOk());

        complete(token, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("movie.bin"))
                .andExpect(jsonPath("$.sizeBytes").value((int) size))
                .andExpect(jsonPath("$.uploadStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.currentVersion").value(1));

        // Exactly one final object remains and its bytes are the chunks
        // concatenated in ascending index order.
        assertThat(finalKeys()).hasSize(1);
        assertThat(stored.get(finalKeys().get(0))).isEqualTo(concat(chunk0, chunk1));

        assertThat(fileRepository.count()).isEqualTo(1);
        List<FileVersion> versions = allVersions();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersionNumber()).isEqualTo(1);
        assertThat(versions.get(0).getObjectKey()).isEqualTo(finalKeys().get(0));
        assertThat(versions.get(0).getSizeBytes()).isEqualTo(size);
        String expectedChecksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(concat(chunk0, chunk1)));
        assertThat(versions.get(0).getChecksum()).isEqualTo(expectedChecksum);
        assertThat(fileRepository.findAll().get(0).getChecksum()).isEqualTo(expectedChecksum);
    }

    @Test
    void rejectsStagedChunkChecksumMismatchWithoutFinalizing() throws Exception {
        double checksumFailures = eventCount("checksum_failure", "failure");
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] bytes = patternBytes(32, 7);
        String sessionId = createSession(token, "checksum.bin", "application/octet-stream", bytes.length);
        putChunk(token, sessionId, 0, bytes).andExpect(status().isOk());
        UploadChunk chunk = uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId)).get(0);
        stored.put(chunk.getStagingObjectKey(), patternBytes(bytes.length, 99));

        complete(token, sessionId).andExpect(status().isBadRequest());
        assertThat(eventCount("checksum_failure", "failure")).isEqualTo(checksumFailures + 1);

        assertThat(fileRepository.count()).isZero();
        assertThat(fileVersionRepository.count()).isZero();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
        assertThat(finalKeys()).isEmpty();
    }

    @Test
    void finalizesZeroByteFileWithOneEmptyChunk() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "empty.txt", "text/plain", 0);

        putChunk(token, sessionId, 0, new byte[0]).andExpect(status().isOk());

        complete(token, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(0))
                .andExpect(jsonPath("$.currentVersion").value(1));

        assertThat(finalKeys()).hasSize(1);
        assertThat(stored.get(finalKeys().get(0))).isEmpty();
        assertThat(allVersions()).hasSize(1);
    }

    // -------------------------------------------------------------------
    // Completeness and size validation
    // -------------------------------------------------------------------

    @Test
    void rejectsIncompleteChunkSet() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        long size = CHUNK_SIZE + 7L;
        String sessionId = createSession(token, "movie.bin", "application/octet-stream", size);

        putChunk(token, sessionId, 0, patternBytes(CHUNK_SIZE, 0)).andExpect(status().isOk());

        complete(token, sessionId).andExpect(status().isBadRequest());

        assertThat(fileRepository.count()).isZero();
        assertThat(finalKeys()).isEmpty();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    @Test
    void rejectsChunkWhoseSizeDoesNotMatchTheDeclaredSplit() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "file.bin", "application/octet-stream", 100);

        // Persist a chunk row directly with a size that cannot come from a
        // well-formed ingestion, simulating a corrupted/inconsistent chunk set.
        UploadSession session = session(sessionId);
        uploadChunkRepository.saveAndFlush(new UploadChunk(
                session, 0, 99L, "deadbeef",
                "_uploads/" + session.getOwner().getId() + "/" + sessionId + "/0"));

        complete(token, sessionId).andExpect(status().isBadRequest());

        assertThat(fileRepository.count()).isZero();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    // -------------------------------------------------------------------
    // New file vs replacement
    // -------------------------------------------------------------------

    @Test
    void replacementCreatesNextVersionAndRetainsHistory() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        byte[] original = patternBytes(64, 1);
        String fileId = uploadAndGetId(token, "report.bin", "application/octet-stream", original);
        FileVersion version1 = onlyVersion();

        byte[] replacement = patternBytes(200, 2);
        String sessionId = createReplacementSession(
                token, "report.bin", "application/octet-stream", replacement.length, fileId);
        putChunk(token, sessionId, 0, replacement).andExpect(status().isOk());

        complete(token, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.sizeBytes").value(replacement.length));

        List<FileVersion> versions = allVersions();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getId()).isEqualTo(version1.getId());
        assertThat(versions.get(0).getObjectKey()).isEqualTo(version1.getObjectKey());
        assertThat(versions.get(1).getVersionNumber()).isEqualTo(2);
        assertThat(versions.get(1).getObjectKey()).isNotEqualTo(version1.getObjectKey());

        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        assertThat(file.getObjectKey()).isEqualTo(versions.get(1).getObjectKey());
        // The replaced version's object is never deleted.
        assertThat(stored).containsKey(version1.getObjectKey());
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.never())
                .delete(version1.getObjectKey());
    }

    // -------------------------------------------------------------------
    // Idempotency and concurrency
    // -------------------------------------------------------------------

    @Test
    void repeatedCompletionIsIdempotent() throws Exception {
        double createCount = sessionTimerCount("create", "success");
        double chunkCount = sessionTimerCount("chunk", "success");
        double completionCount = sessionTimerCount("complete", "success");
        double retries = eventCount("idempotent_retry", "success");
        double cleanups = eventCount("staging_cleanup", "success");
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "once.bin", "application/octet-stream", 128);
        putChunk(token, sessionId, 0, patternBytes(128, 3)).andExpect(status().isOk());
        putChunk(token, sessionId, 0, patternBytes(128, 3)).andExpect(status().isOk());

        String first = complete(token, sessionId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.id");

        // A second completion returns the same logical result without creating a
        // second file, version or object.
        complete(token, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.currentVersion").value(1));

        assertThat(fileRepository.count()).isEqualTo(1);
        assertThat(allVersions()).hasSize(1);
        assertThat(finalKeys()).hasSize(1);
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(sessionTimerCount("create", "success")).isEqualTo(createCount + 1);
        assertThat(sessionTimerCount("chunk", "success")).isEqualTo(chunkCount + 2);
        assertThat(sessionTimerCount("complete", "success")).isEqualTo(completionCount + 2);
        assertThat(eventCount("idempotent_retry", "success")).isEqualTo(retries + 2);
        assertThat(eventCount("staging_cleanup", "success")).isEqualTo(cleanups + 1);
        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape).contains(
                "minidrive_upload_session_operation_seconds_count{application=\"minidrive\",operation=\"create\",outcome=\"success\"}");
    }

    @Test
    void concurrentCompletionCreatesExactlyOneFileAndVersion() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "race.bin", "application/octet-stream", 256);
        putChunk(token, sessionId, 0, patternBytes(256, 4)).andExpect(status().isOk());
        UUID id = UUID.fromString(sessionId);
        Authentication auth = auth("alice@test.com");

        List<Object> results = runConcurrently(
                () -> uploadSessionService.complete(auth, id),
                () -> uploadSessionService.complete(auth, id));

        assertThat(failures(results)).isEmpty();
        assertThat(results).allMatch(FileUploadResponse.class::isInstance);
        List<UUID> fileIds = results.stream()
                .map(FileUploadResponse.class::cast)
                .map(FileUploadResponse::getId)
                .distinct()
                .toList();
        assertThat(fileIds).hasSize(1);

        assertThat(fileRepository.count()).isEqualTo(1);
        assertThat(allVersions()).hasSize(1);
        // The losing attempt removes the object it uploaded; only one remains.
        assertThat(finalKeys()).hasSize(1);
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
    }

    // -------------------------------------------------------------------
    // Failure compensation
    // -------------------------------------------------------------------

    @Test
    void finalStorageWriteFailureLeavesSessionRetryable() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "fail.bin", "application/octet-stream", 100);
        putChunk(token, sessionId, 0, patternBytes(100, 5)).andExpect(status().isOk());

        org.mockito.Mockito.doThrow(new StorageException("storage down"))
                .when(storageService)
                .upload(anyString(), any(InputStream.class), anyLong(), anyString());

        complete(token, sessionId).andExpect(status().isBadGateway());

        assertThat(fileRepository.count()).isZero();
        assertThat(finalKeys()).isEmpty();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
        // Chunks are untouched, so the upload can be resumed/retried.
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).hasSize(1);
    }

    @Test
    void metadataPersistenceFailureRemovesTheFinalObject() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "atomic.bin", "application/octet-stream", 90);
        putChunk(token, sessionId, 0, patternBytes(90, 6)).andExpect(status().isOk());

        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("simulated"))
                .when(fileRepository).saveAndFlush(any(File.class));

        UUID id = UUID.fromString(sessionId);
        assertThatThrownBy(() -> uploadSessionService.complete(auth("alice@test.com"), id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // No metadata was persisted, the session is retryable, and the orphaned
        // final object was removed while the staging chunk stayed.
        assertThat(fileRepository.count()).isZero();
        assertThat(finalKeys()).isEmpty();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
        org.mockito.Mockito.verify(storageService, org.mockito.Mockito.times(1))
                .delete(org.mockito.ArgumentMatchers.argThat(key -> !key.startsWith("_uploads/")));
    }

    @Test
    void filenameConflictDuringFinalizationIsReportedAsAValidationError() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String ownerId = userId("alice@test.com");
        String sessionId = createSession(token, "conflict.bin", "application/octet-stream", 32);
        putChunk(token, sessionId, 0, patternBytes(32, 12)).andExpect(status().isOk());

        // The name pre-check passes, then the insert loses the race: the
        // completion must surface the standard 400 (and remove its orphaned
        // object) rather than reporting a 500.
        org.mockito.Mockito.doReturn(false, true)
                .when(fileRepository)
                .existsByOwnerIdAndFolderIdIsNullAndName(UUID.fromString(ownerId), "conflict.bin");
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_files_owner_folder_name\""))
                .when(fileRepository).saveAndFlush(any(File.class));

        complete(token, sessionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("A file with this name already exists in this folder"));

        assertThat(fileRepository.count()).isZero();
        assertThat(finalKeys()).isEmpty();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    // -------------------------------------------------------------------
    // Staging cleanup
    // -------------------------------------------------------------------

    @Test
    void completionRemovesStagingObjectsAndChunkRows() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "clean.bin", "application/octet-stream", 64);
        putChunk(token, sessionId, 0, patternBytes(64, 7)).andExpect(status().isOk());

        complete(token, sessionId).andExpect(status().isOk());

        assertThat(stored.keySet()).noneMatch(key -> key.startsWith("_uploads/"));
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
    }

    @Test
    void stagingCleanupFailureDoesNotFailACompletedUpload() throws Exception {
        double cleanupFailures = eventCount("staging_cleanup", "failure");
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "tolerant.bin", "application/octet-stream", 64);
        putChunk(token, sessionId, 0, patternBytes(64, 8)).andExpect(status().isOk());

        org.mockito.Mockito.doThrow(new StorageException("delete down"))
                .when(storageService).delete(startsWith("_uploads/"));

        complete(token, sessionId).andExpect(status().isOk());
        assertThat(eventCount("staging_cleanup", "failure")).isEqualTo(cleanupFailures + 1);

        // The completed file is valid even though staging cleanup failed.
        assertThat(fileRepository.count()).isEqualTo(1);
        assertThat(allVersions()).hasSize(1);
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        // Chunk rows are still removed so the session is not left with a resume map.
        assertThat(uploadChunkRepository.findBySessionIdOrderByChunkIndexAsc(
                UUID.fromString(sessionId))).isEmpty();
    }

    // -------------------------------------------------------------------
    // Lifecycle, expiry and authorization
    // -------------------------------------------------------------------

    @Test
    void expiredSessionCannotBeCompleted() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        User owner = userRepository.findByEmail("alice@test.com").orElseThrow();
        UploadSession expired = uploadSessionRepository.saveAndFlush(new UploadSession(
                owner, null, null, "old.bin", "application/octet-stream",
                10, CHUNK_SIZE, 1, UploadSessionStatus.INITIATED,
                LocalDateTime.now().minusMinutes(5)));

        complete(token, expired.getId().toString()).andExpect(status().isBadRequest());

        // The expiry failure happens before any object is written; the empty
        // single chunk does not exist, so nothing was finalized.
        assertThat(fileRepository.count()).isZero();
    }

    @Test
    void abortedSessionCannotBeCompleted() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String sessionId = createSession(token, "cancel.bin", "application/octet-stream", 50);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/uploads/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        complete(token, sessionId).andExpect(status().isBadRequest());
        assertThat(fileRepository.count()).isZero();
    }

    @Test
    void anotherUserCannotCompleteSessionAndAnonymousIsRejected() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String sessionId = createSession(alice, "private.bin", "application/octet-stream", 100);
        putChunk(alice, sessionId, 0, patternBytes(100, 9)).andExpect(status().isOk());

        complete(bob, sessionId).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/uploads/" + sessionId + "/complete"))
                .andExpect(status().isUnauthorized());

        assertThat(fileRepository.count()).isZero();
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    @Test
    void revokedReplacementAccessIsRejectedAtCompletion() throws Exception {
        String alice = registerAndGetToken("Alice", "alice@test.com");
        String bob = registerAndGetToken("Bob", "bob@test.com");
        String fileId = uploadAndGetId(alice, "shared.bin", "application/octet-stream",
                patternBytes(32, 10));
        File file = fileRepository.findById(UUID.fromString(fileId)).orElseThrow();
        User bobUser = userRepository.findByEmail("bob@test.com").orElseThrow();
        Permission permission = permissionRepository.saveAndFlush(
                new Permission(file, bobUser, FileRole.EDITOR));

        byte[] replacement = patternBytes(128, 11);
        String sessionId = createReplacementSession(
                bob, "shared.bin", "application/octet-stream", replacement.length, fileId);
        putChunk(bob, sessionId, 0, replacement).andExpect(status().isOk());

        // Access is revoked before Bob finalizes.
        permissionRepository.delete(permission);
        permissionRepository.flush();

        complete(bob, sessionId).andExpect(status().isForbidden());

        assertThat(allVersions()).hasSize(1);
        assertThat(session(sessionId).getStatus()).isEqualTo(UploadSessionStatus.INITIATED);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, null);
    }

    private byte[] readAll(Object stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = (InputStream) stream) {
            input.transferTo(out);
        }
        return out.toByteArray();
    }

    private List<String> finalKeys() {
        return stored.keySet().stream()
                .filter(key -> !key.startsWith("_uploads/"))
                .sorted()
                .toList();
    }

    private UploadSession session(String sessionId) {
        return uploadSessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
    }

    private List<FileVersion> allVersions() {
        return fileVersionRepository.findAll();
    }

    private FileVersion onlyVersion() {
        List<FileVersion> versions = allVersions();
        assertThat(versions).hasSize(1);
        return versions.get(0);
    }

    private byte[] patternBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ((i + seed) % 251);
        }
        return bytes;
    }

    private byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

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

    private ResultActions complete(String token, String sessionId) throws Exception {
        return mockMvc.perform(post("/api/uploads/" + sessionId + "/complete")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions putChunk(String token, String sessionId, int index, byte[] bytes)
            throws Exception {
        return mockMvc.perform(put("/api/uploads/" + sessionId + "/chunks/" + index)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(bytes));
    }

    private String sessionJson(
            String filename, String contentType, long size, String folderId, String targetFileId) {
        StringBuilder json = new StringBuilder("{\"filename\":\"").append(filename)
                .append("\",\"contentType\":\"").append(contentType)
                .append("\",\"totalSizeBytes\":").append(size);
        if (folderId != null) {
            json.append(",\"folderId\":\"").append(folderId).append("\"");
        }
        if (targetFileId != null) {
            json.append(",\"targetFileId\":\"").append(targetFileId).append("\"");
        }
        return json.append("}").toString();
    }

    private String createSession(String token, String filename, String contentType, long size)
            throws Exception {
        return createSession(token, filename, contentType, size, null);
    }

    private String createSession(
            String token, String filename, String contentType, long size, String folderId)
            throws Exception {
        String body = mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(filename, contentType, size, folderId, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createReplacementSession(
            String token, String filename, String contentType, long size, String targetFileId)
            throws Exception {
        String body = mockMvc.perform(post("/api/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(filename, contentType, size, null, targetFileId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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

    private double sessionTimerCount(String operation, String outcome) {
        var timer = meterRegistry.find("minidrive_upload_session_operation_seconds")
                .tag("operation", operation).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double eventCount(String event, String outcome) {
        var counter = meterRegistry.find("minidrive_upload_session_events_total")
                .tag("event", event).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
