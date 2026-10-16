package com.minidrive.cache;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.config.CacheConfig;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.User;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.PermissionRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.FileListingCache;
import com.minidrive.service.ListingCacheGeneration;
import com.minidrive.storage.StorageService;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural tests for the Phase 10.3 file-listing cache: population, reuse,
 * per-user and per-folder isolation, authorization on hits, invalidation and
 * rollback behaviour, and fail-open handling of a Redis outage.
 *
 * <p>The application's real cache wiring is Redis
 * ({@link CacheConfig} + {@code spring.data.redis}), which is not available in
 * this environment, so the {@link CacheManager} is replaced here by a Mockito
 * test double for the {@link Cache} SPI whose {@code get}/{@code put} behave
 * like a map and whose {@code clear} records the call. That is a test
 * stand-in for the cache interface, never a production substitute: no
 * in-memory cache is added to the application, and the real Redis round-trip
 * (key layout, TTL, JSON value) is covered by
 * {@link FileListingCacheRedisIntegrationTest}, which skips when no server is
 * reachable.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileListingCacheTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private FileListingCache fileListingCache;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private FileRepository fileRepository;
    @MockitoBean private StorageService storageService;
    @MockitoBean private CacheManager cacheManager;
    @MockitoBean private ListingCacheGeneration generationStore;

    /** Entry store behind the mocked {@link Cache}, so hits behave like hits. */
    private final Map<Object, Object> storedEntries = new HashMap<>();

    /**
     * Controllable generation behind the mocked {@link ListingCacheGeneration}.
     * It changes on every {@code advance()} so an invalidation really moves the
     * cache key, exactly as distinct random tokens do in production.
     */
    private final AtomicLong generation = new AtomicLong();

    private Cache cache;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        fileVersionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();

        storedEntries.clear();
        generation.set(1L);
        given(generationStore.current())
                .willAnswer(invocation -> "gen-" + generation.get());
        doAnswer(invocation -> {
            generation.incrementAndGet();
            return null;
        }).when(generationStore).advance();
        cache = org.mockito.Mockito.mock(Cache.class, FileListingCache.CACHE_NAME);
        given(cacheManager.getCache(FileListingCache.CACHE_NAME)).willReturn(cache);
        given(cache.get(any())).willAnswer(invocation -> {
            Object key = invocation.getArgument(0);
            return storedEntries.containsKey(key)
                    ? new SimpleValueWrapper(storedEntries.get(key))
                    : null;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            storedEntries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cache).put(any(), any());
    }

    // ------------------------------------------------------------------
    // 1. An initial request populates the cache.
    // ------------------------------------------------------------------

    @Test
    void initialRequestPopulatesTheCacheUnderAnOwnerAndFolderKey() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        createFile(token, "report.pdf", null, "application/pdf", 1024);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("report.pdf"));

        verify(cache).put(org.mockito.ArgumentMatchers.eq(aliceId + ":root:" + currentToken()), any());
        assertThat(storedEntries).hasSize(1);
        assertThat(listingEntries(aliceId + ":root:" + currentToken()))
                .hasSize(1)
                .allSatisfy(summary -> assertThat(summary.getName()).isEqualTo("report.pdf"));
    }

    // ------------------------------------------------------------------
    // 2. A repeated eligible request uses cached metadata.
    // ------------------------------------------------------------------

    @Test
    void repeatedRequestIsServedFromCacheWithoutRequeryingTheDatabase() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        createFile(token, "first.txt", null, "text/plain", 10);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)));

        // A file inserted behind the service's back (no mutation, therefore no
        // eviction): a cached answer still reports exactly one file.
        User alice = userRepository.findByEmail("alice@test.com").orElseThrow();
        File unlistedByCache = new File(alice, null, "second.txt", "text/plain", 20, null);
        unlistedByCache.setObjectKey("files/" + aliceId + "/" + UUID.randomUUID());
        fileRepository.saveAndFlush(unlistedByCache);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("first.txt"));

        verify(fileRepository, times(1))
                .findByOwnerIdAndFolderIdIsNull(UUID.fromString(aliceId));
    }

    // ------------------------------------------------------------------
    // 3. Different users cannot receive each other's cached results.
    // ------------------------------------------------------------------

    @Test
    void differentUsersNeverReceiveEachOthersCachedEntries() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        createFile(aliceToken, "alice.txt", null, "text/plain", 1);
        createFile(bobToken, "bob.txt", null, "text/plain", 2);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("alice.txt"));

        // Alice's entry is still cached while Bob asks: it must not be reused.
        assertThat(storedEntries).hasSize(1);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("bob.txt"));

        assertThat(storedEntries).hasSize(2);
        assertThat(storedEntries).containsKeys(
                userId("alice@test.com") + ":root:" + currentToken(),
                userId("bob@test.com") + ":root:" + currentToken());
        assertThat(listingEntries(userId("alice@test.com") + ":root:" + currentToken()))
                .allSatisfy(summary -> assertThat(summary.getName()).isEqualTo("alice.txt"));
        assertThat(listingEntries(userId("bob@test.com") + ":root:" + currentToken()))
                .allSatisfy(summary -> assertThat(summary.getName()).isEqualTo("bob.txt"));
    }

    // ------------------------------------------------------------------
    // 4. Different folders or other query parameters do not collide.
    // ------------------------------------------------------------------

    @Test
    void differentFoldersAndTheRootListingDoNotCollide() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        String documents = createFolder(token, "Documents");
        String photos = createFolder(token, "Photos");
        createFile(token, "root.txt", null, "text/plain", 1);
        createFile(token, "notes.txt", documents, "text/plain", 2);
        createFile(token, "holiday.jpg", photos, "image/jpeg", 3);

        mockMvc.perform(get("/api/files").param("folderId", documents)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("notes.txt"));

        mockMvc.perform(get("/api/files").param("folderId", photos)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("holiday.jpg"));

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("root.txt"));

        assertThat(storedEntries).hasSize(3);
        assertThat(storedEntries).containsKeys(
                aliceId + ":root:" + currentToken(),
                aliceId + ":" + documents + ":" + currentToken(),
                aliceId + ":" + photos + ":" + currentToken());
        assertThat(listingEntries(aliceId + ":" + documents + ":" + currentToken()).get(0).getName())
                .isEqualTo("notes.txt");
        assertThat(listingEntries(aliceId + ":" + photos + ":" + currentToken()).get(0).getName())
                .isEqualTo("holiday.jpg");
    }

    // ------------------------------------------------------------------
    // 5. Authorization is still evaluated on cache hits.
    // ------------------------------------------------------------------

    @Test
    void anotherUsersFolderIsRejectedBeforeTheCacheIsEverConsulted() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceFolder = createFolder(aliceToken, "Alice folder");
        createFile(aliceToken, "private.txt", aliceFolder, "text/plain", 1);

        mockMvc.perform(get("/api/files").param("folderId", aliceFolder)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(1)));
        assertThat(storedEntries)
                .containsKey(userId("alice@test.com") + ":" + aliceFolder + ":" + currentToken());

        org.mockito.Mockito.clearInvocations(cache, generationStore);

        mockMvc.perform(get("/api/files").param("folderId", aliceFolder)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // Ownership is validated first, so neither the generation nor a warm
        // entry for another user's folder is ever looked up.
        verifyNoInteractions(cache);
        verifyNoInteractions(generationStore);
    }

    @Test
    void cachedOwnerListingIsNotServedOnceTheFolderIsNoLongerAccessible() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        String folderId = createFolder(token, "Documents");
        createFile(token, "notes.txt", folderId, "text/plain", 1);

        mockMvc.perform(get("/api/files").param("folderId", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)));

        String cachedKey = aliceId + ":" + folderId + ":" + currentToken();
        assertThat(storedEntries).containsKey(cachedKey);

        // The folder disappears behind the service's back: the entry is still
        // cached, yet the existence/ownership check must still decide the answer.
        fileRepository.deleteAll(fileRepository.findByOwnerIdAndFolderId(
                UUID.fromString(aliceId), UUID.fromString(folderId)));
        folderRepository.deleteById(UUID.fromString(folderId));
        folderRepository.flush();
        org.mockito.Mockito.clearInvocations(cache, generationStore);

        mockMvc.perform(get("/api/files").param("folderId", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertThat(storedEntries).containsKey(cachedKey);
        verify(cache, never()).get(cachedKey);
        verify(generationStore, never()).current();
    }

    // ------------------------------------------------------------------
    // 6. File and permission mutations invalidate affected cached data.
    // ------------------------------------------------------------------

    @Test
    void creatingAFileInvalidatesTheCachedListing() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        createFile(token, "first.txt", null, "text/plain", 1);
        warmRootListing(token);

        createFile(token, "second.txt", null, "text/plain", 2);

        verify(generationStore).advance();
        // The next read resolves the new generation and repopulates from the DB.
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void renamingAndMovingAFileInvalidatesTheCachedListing() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolder(token, "Documents");
        String fileId = createFile(token, "draft.txt", null, "text/plain", 1);
        warmRootListing(token);

        mockMvc.perform(patch("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"final.txt\",\"folderId\":\"" + folderId + "\"}"))
                .andExpect(status().isOk());

        verify(generationStore).advance();
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/files").param("folderId", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("final.txt"));
    }

    @Test
    void replacingContentInvalidatesTheCachedListing() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFile(token, "notes.txt", null, "text/plain", 1);
        warmRootListing(token);

        mockMvc.perform(multipart("/api/files/" + fileId + "/content")
                        .file(new MockMultipartFile(
                                "file", "notes.txt", "text/plain", "replaced".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(generationStore).advance();
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].sizeBytes").value("replaced".length()))
                .andExpect(jsonPath("$[0].currentVersion").value(1));
    }

    @Test
    void deletingAFileInvalidatesTheCachedListing() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String fileId = createFile(token, "temp.txt", null, "text/plain", 1);
        warmRootListing(token);

        mockMvc.perform(delete("/api/files/" + fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        verify(generationStore).advance();
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deletingAFolderInvalidatesTheCachedListingOfItsFiles() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String folderId = createFolder(token, "Documents");
        createFile(token, "notes.txt", folderId, "text/plain", 1);

        mockMvc.perform(get("/api/files").param("folderId", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)));
        assertThat(storedEntries).hasSize(1);
        org.mockito.Mockito.clearInvocations(cache, generationStore);

        mockMvc.perform(delete("/api/folders/" + folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        verify(generationStore).advance();
    }

    @Test
    void permissionChangesLeaveTheListingCacheUntouched() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        registerAndGetToken("Bob", "bob@test.com");
        String fileId = createFile(aliceToken, "shared.txt", null, "text/plain", 1);
        warmRootListing(aliceToken);

        mockMvc.perform(post("/api/files/" + fileId + "/permissions")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@test.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/files/" + fileId + "/permissions/"
                        + userId("bob@test.com"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        // A listing only ever contains the owner's own files and never carries
        // permission data, so granting or revoking access cannot change it: no
        // invalidation is warranted and none happens.
        verify(generationStore, never()).advance();
        assertThat(storedEntries).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 7. Rollback does not leave incorrect cache state.
    // ------------------------------------------------------------------

    @Test
    void failedMutationLeavesTheCacheUntouched() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        warmRootListing(token);

        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated"))
                .when(fileRepository)
                .saveAndFlush(any(File.class));

        // The persistence failure propagates (as every existing metadata-failure
        // test expects) and the transaction rolls back. The service therefore
        // returns before ever registering an eviction, and the cache keeps the
        // entries that still match the rolled-back-to database state.
        assertThatThrownBy(() -> mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile(
                                "file", "doomed.txt", "text/plain", new byte[]{1}))
                        .header("Authorization", "Bearer " + token)))
                .isInstanceOf(ServletException.class);

        assertThat(fileRepository.count()).isZero();
        verify(generationStore, never()).advance();
        assertThat(storedEntries).hasSize(1);
    }

    @Test
    void evictionIsDeferredUntilTheTransactionCommitsAndSkippedOnRollback() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.execute(status -> {
            fileListingCache.evictAfterCommit();
            status.setRollbackOnly();
            return null;
        });
        verify(generationStore, never()).advance();

        transaction.execute(status -> {
            fileListingCache.evictAfterCommit();
            return null;
        });
        verify(generationStore).advance();
    }

    @Test
    void evictionWithoutATransactionAdvancesImmediately() {
        fileListingCache.evictAfterCommit();

        verify(generationStore).advance();
    }

    // ------------------------------------------------------------------
    // 5 (runtime). A Redis outage fails open, is logged, and never masks
    // authorization or database errors.
    // ------------------------------------------------------------------

    @Test
    void redisOutageFailsOpenAndIsLogged() throws Exception {
        Logger cacheLogger = (Logger) LoggerFactory.getLogger(FileListingCache.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        cacheLogger.addAppender(appender);

        try {
            String token = registerAndGetToken("Alice", "alice@test.com");
            String fileId = createFile(token, "notes.txt", null, "text/plain", 3);

            given(cache.get(any())).willThrow(
                    new RedisConnectionFailureException("redis is down"));
            org.mockito.Mockito.doThrow(new RedisConnectionFailureException("redis is down"))
                    .when(cache).put(any(), any());

            mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("notes.txt"));

            mockMvc.perform(delete("/api/files/" + fileId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("Redis"));

            // Authorization still decides the answer while the cache is down.
            mockMvc.perform(get("/api/files").param("folderId", UUID.randomUUID().toString())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        } finally {
            cacheLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ------------------------------------------------------------------
    // 8. The stale-repopulation race: a delayed reader that captured an old
    //    generation cannot make its result visible to current requests.
    // ------------------------------------------------------------------

    @Test
    void delayedReaderUnderAnOldGenerationCannotRepopulateTheCurrentGeneration() throws Exception {

        registerAndGetToken("Alice", "alice@test.com");
        User alice = userRepository.findByEmail("alice@test.com").orElseThrow();
        UUID aliceId = alice.getId();

        // The already-committed state the delayed reader will load.
        File first = new File(alice, null, "first.txt", "text/plain", 3, null);
        first.setObjectKey("files/" + aliceId + "/" + UUID.randomUUID());
        fileRepository.saveAndFlush(first);

        // Hold the delayed reader at its cache write, after it has resolved its
        // generation and loaded the database snapshot it is about to publish.
        CountDownLatch readerReachedCacheWrite = new CountDownLatch(1);
        CountDownLatch allowReaderToFinish = new CountDownLatch(1);
        AtomicBoolean firstPut = new AtomicBoolean(true);

        doAnswer(invocation -> {
            storedEntries.put(invocation.getArgument(0), invocation.getArgument(1));
            if (firstPut.compareAndSet(true, false)) {
                readerReachedCacheWrite.countDown();
                allowReaderToFinish.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(cache).put(any(), any());

        AtomicReference<List<FileResponse>> delayedResult = new AtomicReference<>();
        Thread delayedReader = new Thread(() ->
                delayedResult.set(fileListingCache.findOwnedFileSummaries(aliceId, null)));
        delayedReader.start();

        assertThat(readerReachedCacheWrite.await(5, TimeUnit.SECONDS)).isTrue();

        // A mutation commits while the reader is in flight: the database gains a
        // second file and the generation advances (what evictAfterCommit does).
        File committed = new File(alice, null, "committed.txt", "text/plain", 7, null);
        committed.setObjectKey("files/" + aliceId + "/" + UUID.randomUUID());
        fileRepository.saveAndFlush(committed);
        fileListingCache.evictAfterCommit();

        allowReaderToFinish.countDown();
        delayedReader.join(5000);

        // The delayed reader published its pre-commit snapshot (one file) under
        // the generation it captured before the mutation.
        assertThat(delayedResult.get()).hasSize(1);
        assertThat(listingEntries(aliceId + ":root:gen-1"))
                .hasSize(1)
                .allSatisfy(summary -> assertThat(summary.getName()).isEqualTo("first.txt"));

        // A current request must not see that stale write: it resolves the new
        // generation, misses, and loads the committed state from the database.
        List<FileResponse> current = fileListingCache.findOwnedFileSummaries(aliceId, null);
        assertThat(current).extracting(FileResponse::getName)
                .containsExactlyInAnyOrder("first.txt", "committed.txt");

        // The stale write stays confined to the old generation; the current
        // generation holds the fresh, complete result.
        assertThat(listingEntries(aliceId + ":root:gen-2")).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The generation token the mocked store is currently handing out. */
    private String currentToken() {
        return "gen-" + generation.get();
    }

    @SuppressWarnings("unchecked")
    private List<FileResponse> listingEntries(String key) {

        return new ArrayList<>((List<FileResponse>) storedEntries.get(key));
    }

    private void warmRootListing(String token) throws Exception {
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(cache).put(any(), any());
        org.mockito.Mockito.clearInvocations(cache, generationStore);
    }

    private String createFile(
            String token, String name, String folderId, String contentType, long sizeBytes)
            throws Exception {

        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\"");
        if (folderId != null) {
            body.append(",\"folderId\":\"").append(folderId).append("\"");
        }
        body.append(",\"contentType\":\"").append(contentType).append("\"");
        body.append(",\"sizeBytes\":").append(sizeBytes).append("}");

        String response = mockMvc.perform(post("/api/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.id");
    }

    private String createFolder(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/folders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.id");
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
