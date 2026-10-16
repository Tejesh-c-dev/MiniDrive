package com.minidrive.cache;

import com.jayway.jsonpath.JsonPath;
import com.minidrive.config.CacheConfig;
import com.minidrive.entity.File;
import com.minidrive.entity.User;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.FileVersionRepository;
import com.minidrive.repository.FolderRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.FileListingCache;
import com.minidrive.service.ListingCacheGeneration;
import com.minidrive.storage.StorageService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-server integration test for the Phase 10.3 file-listing cache.
 *
 * <p>Requires an actual Redis server, so it exercises the production wiring
 * end to end: the real {@link CacheConfig} {@code RedisCacheManager} (not the
 * test double used by {@link FileListingCacheTest}), a real database, and HTTP
 * requests through the listing endpoints, with the resulting Redis key, TTL and
 * JSON value inspected directly.</p>
 *
 * <p>When no server answers at {@code REDIS_HOST}:{@code REDIS_PORT} (the
 * Compose stack publishes one on {@code 127.0.0.1:56379}) every test in this
 * class is a genuine {@code Skipped} outcome, following
 * {@link RedisConnectivityIntegrationTest}: connectivity is never faked with an
 * embedded or in-memory substitute, and skips are reported as skips.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FileListingCacheRedisIntegrationTest {

    private static final String HOST =
            System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "56379"));

    private static final String PASSWORD = "password123";

    /**
     * Points the application's own Redis client at the server under test, so the
     * cache manager the application actually uses is the one being verified.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> HOST);
        registry.add("spring.data.redis.port", () -> PORT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @Autowired private RedisConnectionFactory connectionFactory;
    @MockitoSpyBean private FileRepository fileRepository;
    @MockitoBean private StorageService storageService;

    private RedisTemplate<String, String> rawRedis;

    @BeforeEach
    void setUp() {
        assumeServerReachable();

        rawRedis = new RedisTemplate<>();
        rawRedis.setConnectionFactory(connectionFactory);
        rawRedis.setKeySerializer(RedisSerializer.string());
        rawRedis.setValueSerializer(RedisSerializer.string());
        rawRedis.afterPropertiesSet();

        clearListingCache();
        fileVersionRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (rawRedis != null) {
            clearListingCache();
        }
    }

    @Test
    void requestStoresTheListingInRedisAsJsonUnderTheOwnerKeyWithTtl() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        createFile(token, "report.pdf", null, "application/pdf", 1024);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        String redisKey = listingRedisKey(aliceId + ":root", currentGeneration());

        assertThat(rawRedis.keys(CacheConfig.CACHE_KEY_PREFIX + "*")).contains(redisKey);

        String json = rawRedis.opsForValue().get(redisKey);
        assertThat(json)
                .contains("com.minidrive.dto.file.FileResponse")
                .contains("\"name\":\"report.pdf\"")
                .contains("\"sizeBytes\":1024")
                .contains("\"currentVersion\"")
                .doesNotContain("\"objectKey\"");

        Long ttl = rawRedis.getExpire(redisKey);
        assertThat(ttl).isNotNull();
        // The 60-second TTL is inherited from CacheConfig, not overridden here.
        assertThat(ttl).isBetween(55L, 60L);
    }

    @Test
    void repeatedRequestIsServedFromRedisAndKeysAreScopedPerUserAndFolder() throws Exception {
        String aliceToken = registerAndGetToken("Alice", "alice@test.com");
        String bobToken = registerAndGetToken("Bob", "bob@test.com");
        String aliceId = userId("alice@test.com");
        String bobId = userId("bob@test.com");
        String aliceFolder = createFolder(aliceToken, "Documents");
        createFile(aliceToken, "alice.txt", null, "text/plain", 1);
        createFile(aliceToken, "notes.txt", aliceFolder, "text/plain", 2);
        createFile(bobToken, "bob.txt", null, "text/plain", 3);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("alice.txt"));

        // A file inserted behind the service's back (no mutation, so no
        // eviction) changes nothing while the Redis entry is still alive: the
        // repeat request is answered from Redis, not from PostgreSQL.
        User alice = userRepository.findByEmail("alice@test.com").orElseThrow();
        File unlistedByCache = new File(alice, null, "second.txt", "text/plain", 20, null);
        unlistedByCache.setObjectKey("files/" + aliceId + "/" + UUID.randomUUID());
        fileRepository.saveAndFlush(unlistedByCache);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("alice.txt"));

        verify(fileRepository, times(1))
                .findByOwnerIdAndFolderIdIsNull(UUID.fromString(aliceId));

        mockMvc.perform(get("/api/files").param("folderId", aliceFolder)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].name").value("notes.txt"));
        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("bob.txt"));

        String generation = currentGeneration();
        assertThat(rawRedis.keys(CacheConfig.CACHE_KEY_PREFIX + "*")).containsExactlyInAnyOrder(
                listingRedisKey(aliceId + ":root", generation),
                listingRedisKey(aliceId + ":" + aliceFolder, generation),
                listingRedisKey(bobId + ":root", generation),
                ListingCacheGeneration.GENERATION_KEY);
    }

    @Test
    void mutationAdvancesTheGenerationSoTheStaleEntryIsNoLongerRead() throws Exception {
        String token = registerAndGetToken("Alice", "alice@test.com");
        String aliceId = userId("alice@test.com");
        createFile(token, "first.txt", null, "text/plain", 1);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(1)));

        String generationBefore = currentGeneration();
        String staleKey = listingRedisKey(aliceId + ":root", generationBefore);
        assertThat(rawRedis.keys(CacheConfig.CACHE_KEY_PREFIX + "*")).contains(staleKey);

        createFile(token, "second.txt", null, "text/plain", 2);

        // The committed mutation advanced the generation in Redis. The previous
        // entry is left to expire on its TTL and is no longer read.
        String generationAfter = currentGeneration();
        assertThat(generationAfter).isNotEqualTo(generationBefore);

        mockMvc.perform(get("/api/files").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$", hasSize(2)));
        assertThat(rawRedis.keys(CacheConfig.CACHE_KEY_PREFIX + "*"))
                .contains(listingRedisKey(aliceId + ":root", generationAfter));
    }

    /** The Redis key the listing cache stores one owner/folder entry under. */
    private static String listingRedisKey(String suffix, String generation) {
        return CacheConfig.CACHE_KEY_PREFIX + FileListingCache.CACHE_NAME + "::"
                + suffix + ":" + generation;
    }

    /** The current shared generation token, read straight from Redis. */
    private String currentGeneration() {
        return rawRedis.opsForValue().get(ListingCacheGeneration.GENERATION_KEY);
    }

    private void assumeServerReachable() {
        if (!serverReachable()) {
            throw new TestAbortedException(
                    "No Redis server reachable at " + HOST + ":" + PORT
                            + " (start the Compose stack: docker compose -f infra/docker-compose.yml up -d redis)");
        }
    }

    private boolean serverReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void clearListingCache() {
        Set<String> keys = rawRedis.keys(CacheConfig.CACHE_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            rawRedis.delete(keys);
        }
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
