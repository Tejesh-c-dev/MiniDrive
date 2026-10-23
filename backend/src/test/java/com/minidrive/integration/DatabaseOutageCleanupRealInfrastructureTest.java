package com.minidrive.integration;

import com.minidrive.entity.User;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.exception.StorageException;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.CleanupIntentJournal;
import com.minidrive.service.FileService;
import com.minidrive.service.FolderDeletionLifecycle;
import com.minidrive.storage.StorageService;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Opt-in end-to-end check using a real PostgreSQL database and Silo/MinIO bucket. */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_URL:jdbc:postgresql://127.0.0.1:55432/minidrive}",
        "spring.datasource.username=${DB_USERNAME:minidrive}",
        "spring.datasource.password=${DB_PASSWORD:minidrive}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "minidrive.cleanup-journal.initial-delay-ms=3600000",
        "minidrive.cleanup-journal.retry-delay-ms=3600000",
        "minidrive.folder-deletion.retry-delay-ms=3600000",
        "minidrive.cleanup-journal.directory=${java.io.tmpdir}/minidrive-live-cleanup-${random.uuid}"
})
@EnabledIfSystemProperty(named = "minidrive.live-pg-minio", matches = "true")
class DatabaseOutageCleanupRealInfrastructureTest {
    private static final String FILE_CONSTRAINT = "ck_md001_live_file_write";

    @Autowired private FileService fileService;
    @Autowired private FolderDeletionLifecycle lifecycle;
    @Autowired private CleanupIntentJournal journal;
    @Autowired private FileRepository files;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StorageService storage;

    private User owner;
    private MinioClient minio;
    private String bucket;
    private ContainerSnapshot postgresBefore;
    private String postgresId;
    private FileUploadResponse committedFile;
    private UsernamePasswordAuthenticationToken auth;
    private String testOrphanKey;
    private long fileCountBeforeTestData;

    private static final String POSTGRES_CONTAINER = "minidrive-postgres";
    private static final String POSTGRES_JDBC = "jdbc:postgresql://127.0.0.1:55432/minidrive";
    private static final Duration CONTAINER_TIMEOUT = Duration.ofSeconds(90);

    @DynamicPropertySource
    static void liveInfrastructureProperties(DynamicPropertyRegistry properties) {
        properties.add("minio.endpoint", () -> System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000"));
        properties.add("minio.access-key", () -> System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin"));
        properties.add("minio.secret-key", () -> System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin"));
        properties.add("minio.bucket", () -> System.getenv().getOrDefault("MINIO_BUCKET", "minidrive-files"));
    }

    @BeforeEach void prepare() {
        assertEquals("true", System.getenv("MINIDRIVE_ALLOW_POSTGRES_CONTAINER_STOP"),
                "Set MINIDRIVE_ALLOW_POSTGRES_CONTAINER_STOP=true to permit stopping the verified local test container");
        assertEquals(POSTGRES_JDBC, System.getenv().getOrDefault("DB_URL", POSTGRES_JDBC),
                "The outage test may only use its fixed loopback PostgreSQL endpoint");
        postgresBefore = inspectPostgresContainer();
        assertTrue(postgresBefore.running(), "The verified local PostgreSQL container must be running before the test");
        postgresId = postgresBefore.id();
        jdbc.execute("ALTER TABLE file_versions DROP CONSTRAINT IF EXISTS " + FILE_CONSTRAINT);
        owner = users.saveAndFlush(new User("MD001", "md001-" + UUID.randomUUID() + "@test.local", "hash"));
        bucket = System.getProperty("MINIO_BUCKET", System.getenv().getOrDefault("MINIO_BUCKET", "minidrive-files"));
        minio = MinioClient.builder()
                .endpoint(System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000"))
                .credentials(System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin"),
                        System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin"))
                .build();
        auth = new UsernamePasswordAuthenticationToken(owner.getEmail(), "");
        fileCountBeforeTestData = files.count();
        committedFile = fileService.upload(auth,
                new MockMultipartFile("file", "committed-" + UUID.randomUUID() + ".txt", "text/plain",
                        "committed-silo-bytes".getBytes()), null);
    }

    @AfterEach void restoreAndClean() throws Exception {
        if (postgresBefore == null) return;
        RuntimeException cleanupFailure = null;
        try {
            restoreOriginalContainerState();
            if (postgresBefore.running()) {
                jdbc.execute("ALTER TABLE file_versions DROP CONSTRAINT IF EXISTS " + FILE_CONSTRAINT);
                jdbc.execute("DROP TABLE IF EXISTS md004_file_delete_guard");
                // Reconcile only this run's unique journal namespace and object key.
                if (testOrphanKey != null) lifecycle.replayCleanupJournal();
                if (committedFile != null && files.existsById(committedFile.getId()))
                    fileService.delete(auth, committedFile.getId());
                if (owner != null && users.existsById(owner.getId())) users.deleteById(owner.getId());
            }
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        } finally {
            restoreOriginalContainerState();
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }

    @Test void realObjectWriteAndRecoveryUsePostgresAndSiloAcrossFailedJobPersistence() throws Exception {
        // NOT VALID avoids scanning unrelated historical rows while PostgreSQL
        // still enforces the check for the new failed upload.
        jdbc.execute("ALTER TABLE file_versions ADD CONSTRAINT " + FILE_CONSTRAINT
                + " CHECK (version_number <> 1) NOT VALID");
        AtomicBoolean failFirstCompensation = new AtomicBoolean(true);
        AtomicReference<String> writtenKey = new AtomicReference<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            writtenKey.set(key);
            testOrphanKey = key;
            return invocation.callRealMethod();
        }).when(storage).upload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        doAnswer(invocation -> {
            if (failFirstCompensation.getAndSet(false)) {
                // This callback runs after the real Silo write and failed DB operation,
                // immediately before FileService attempts its compensation and fallback job.
                runDocker("stop", postgresId);
                awaitContainerState(false);
                assertPostgresNotAcceptingConnections();
                throw new StorageException("injected compensation outage after real PostgreSQL stop");
            }
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());

        assertThrows(RuntimeException.class, () -> fileService.upload(auth,
                new MockMultipartFile("file", "cross-store.txt", "text/plain", "real-silo-bytes".getBytes()), null));
        assertFalse(inspectPostgresContainer().running(), "PostgreSQL must be genuinely stopped during fallback persistence");

        String orphanKey = writtenKey.get();
        assertNotNull(orphanKey);
        assertTrue(journal.pending().stream().anyMatch(i -> i.objectKey().equals(orphanKey)));
        assertNotNull(minio.statObject(StatObjectArgs.builder().bucket(bucket).object(orphanKey).build()));

        runDocker("start", postgresId);
        awaitContainerState(true);
        awaitPostgresAcceptingConnections();
        jdbc.execute("ALTER TABLE file_versions DROP CONSTRAINT " + FILE_CONSTRAINT);
        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key=?",
                Integer.class, orphanKey), "routing location insert rolled back with the failed upload transaction");
        assertEquals(0, jdbc.queryForObject("select count(*) from folder_deletion_objects where object_key=?",
                Integer.class, orphanKey), "PostgreSQL outage must prevent fallback job persistence");
        assertEquals(1, files.findByOwnerId(owner.getId()).size(), "only the committed test file should remain in DB");
        assertEquals(fileCountBeforeTestData + 1, files.count(),
                "pre-existing unrelated database files must remain unchanged");
        assertTrue(files.existsById(committedFile.getId()));

        // Recovery performs a real routed Silo delete after PostgreSQL accepts connections.
        lifecycle.replayCleanupJournal();

        assertTrue(journal.pending().stream().noneMatch(i -> i.objectKey().equals(orphanKey)));
        assertEquals(0, jdbc.queryForObject("select count(*) from object_locations where object_key=?", Integer.class, orphanKey));
        assertThrows(Exception.class,
                () -> minio.statObject(StatObjectArgs.builder().bucket(bucket).object(orphanKey).build()));
        assertNotNull(minio.statObject(StatObjectArgs.builder().bucket(bucket)
                .object(committedFile.getObjectKey()).build()), "replay must preserve committed objects");
        assertTrue(files.existsById(committedFile.getId()), "replay must preserve committed database state");
    }

    @Test void realPostgresDeleteRollbackLeavesSiloObjectAndMetadataIntact() throws Exception {
        String guard = "md004_file_delete_guard";
        jdbc.execute("DROP TABLE IF EXISTS " + guard);
        jdbc.execute("CREATE TABLE " + guard + "(file_id UUID NOT NULL REFERENCES files(id))");
        jdbc.update("INSERT INTO " + guard + "(file_id) VALUES (?)", committedFile.getId());
        String key = committedFile.getObjectKey();

        // PostgreSQL rejects the metadata DELETE. The durable manifest and
        // metadata change roll back together, so FileService must not yet call
        // Silo and the existing bytes remain readable after this failure.
        assertThrows(RuntimeException.class, () -> fileService.delete(auth, committedFile.getId()));
        assertTrue(files.existsById(committedFile.getId()));
        assertNotNull(minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));

        jdbc.execute("DROP TABLE " + guard);
        fileService.delete(auth, committedFile.getId());
        assertFalse(files.existsById(committedFile.getId()));
        assertThrows(Exception.class, () -> minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));
    }

    private void restoreOriginalContainerState() {
        if (postgresBefore == null || postgresId == null) return;
        ContainerSnapshot current = inspectPostgresContainer();
        if (!current.id().equals(postgresId))
            throw new IllegalStateException("The verified PostgreSQL container identity changed during the test");
        if (postgresBefore.running() && !current.running()) {
            runDocker("start", postgresId);
            awaitContainerState(true);
            awaitPostgresAcceptingConnections();
        } else if (!postgresBefore.running() && current.running()) {
            runDocker("stop", postgresId);
            awaitContainerState(false);
        }
    }

    private ContainerSnapshot inspectPostgresContainer() {
        String json = runDocker("inspect", "--format", "{{json .}}", POSTGRES_CONTAINER);
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            String id = root.path("Id").asText();
            String name = root.path("Name").asText();
            JsonNode config = root.path("Config");
            JsonNode labels = config.path("Labels");
            JsonNode port = root.path("HostConfig").path("PortBindings").path("5432/tcp").path(0);
            boolean intended = name.equals("/" + POSTGRES_CONTAINER)
                    && config.path("Image").asText().equals("postgres:16")
                    && labels.path("com.docker.compose.service").asText().equals("postgres")
                    && labels.path("com.docker.compose.project").asText().equals("infra")
                    && config.path("Env").toString().contains("POSTGRES_USER=minidrive")
                    && config.path("Env").toString().contains("POSTGRES_DB=minidrive")
                    && Path.of(labels.path("com.docker.compose.project.config_files").asText())
                            .toAbsolutePath().normalize().equals(expectedComposeFile())
                    && port.path("HostIp").asText().equals("127.0.0.1")
                    && port.path("HostPort").asText().equals("55432")
                    && root.path("HostConfig").path("Binds").toString().contains("postgres_data:/var/lib/postgresql/data");
            if (id.isBlank() || !intended)
                throw new IllegalStateException("Refusing to control a container not positively identified as this repository's local test PostgreSQL");
            return new ContainerSnapshot(id, root.path("State").path("Running").asBoolean());
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Unable to verify the configured local PostgreSQL container", failure);
        }
    }

    private static Path expectedComposeFile() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && directory != null; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("infra/docker-compose.yml").normalize();
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Unable to locate this repository's infra/docker-compose.yml");
    }

    private void awaitContainerState(boolean running) {
        Instant deadline = Instant.now().plus(CONTAINER_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (inspectPostgresContainer().running() == running) return;
            pauseBriefly();
        }
        throw new IllegalStateException("Timed out waiting for PostgreSQL container running=" + running);
    }

    private void awaitPostgresAcceptingConnections() {
        Instant deadline = Instant.now().plus(CONTAINER_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                String readiness = runDocker("exec", postgresId, "pg_isready", "-U", "minidrive", "-d", "minidrive");
                if (readiness.contains("accepting connections")) return;
            } catch (RuntimeException notReadyYet) {
                // pg_isready exits nonzero until PostgreSQL can accept protocol connections.
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 55432), 500);
                // Require PostgreSQL's own readiness response, not just an open TCP port.
            } catch (Exception unavailable) {
                pauseBriefly();
            }
            if (Instant.now().isBefore(deadline)) {
                pauseBriefly();
            }
        }
        throw new IllegalStateException("PostgreSQL container started but did not accept TCP connections in time");
    }

    private void assertPostgresNotAcceptingConnections() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 55432), 500);
            fail("PostgreSQL still accepted TCP connections after its container was stopped");
        } catch (java.net.ConnectException expected) { /* Connection refusal confirms outage. */ }
        catch (java.io.IOException expected) { /* Any socket I/O failure confirms no accepting listener. */ }
    }

    private static void pauseBriefly() {
        try { Thread.sleep(200); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static String runDocker(String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Docker command timed out: " + command);
            }
            String result = new String(output, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) throw new IllegalStateException("Docker command failed: " + command + " :: " + result);
            return result;
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Unable to run Docker command", failure);
        }
    }

    private record ContainerSnapshot(String id, boolean running) { }
}
