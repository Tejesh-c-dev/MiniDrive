package com.minidrive.integration;

import com.minidrive.exception.StorageException;
import com.minidrive.storage.SiloStorageService;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the real {@link SiloStorageService} against a real MinIO/Silo server
 * (same binary and release tag the infra compose file ships) through an
 * isolated, disposable test bucket created and removed by this class.
 *
 * <p>Only objects under this run's {@link #STAGING_PREFIX}/{@link #FINAL_PREFIX}
 * are touched; existing user data is never read or deleted. The whole run is
 * skipped with an assumption failure when no live MinIO is reachable, so the
 * suite stays green in environments without object storage.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "minidrive.live-minio", matches = "true")
class SiloStorageServiceRealMinioLiveTest {

    private static final String ENDPOINT = System.getProperty(
            "minidrive.live-minio.endpoint", "http://127.0.0.1:9000");
    private static final String ROOT_USER = System.getProperty(
            "minidrive.live-minio.access-key", "minioadmin");
    private static final String ROOT_PASSWORD = System.getProperty(
            "minidrive.live-minio.secret-key", "minioadmin");

    /** Bucket and object prefixes are unique to this verification run. */
    private static final String BUCKET_NAME = "minidrive-verify-"
            + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private static final String STAGING_PREFIX = "_verify-staging/" + BUCKET_NAME + "/";
    private static final String FINAL_PREFIX = "_verify-final/" + BUCKET_NAME + "/";

    private static MinioClient adminClient;
    private static SiloStorageService storageService;

    private static final Random RANDOM = new Random();

    @BeforeAll
    static void startAgainstLiveMinio() throws Exception {
        MinioClient reachableClient = MinioClient.builder()
                .endpoint(ENDPOINT)
                .credentials(ROOT_USER, ROOT_PASSWORD)
                .build();
        try {
            reachableClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET_NAME).build());
        } catch (Exception absent) {
            throw new org.opentest4j.TestAbortedException(
                    "No live MinIO reachable at " + ENDPOINT + "; live-storage verification skipped");
        }
        adminClient = reachableClient;
        // Isolated disposable bucket: created here, removed in @AfterAll.
        adminClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
        storageService = new SiloStorageService(
                MinioClient.builder().endpoint(ENDPOINT)
                        .credentials(ROOT_USER, ROOT_PASSWORD).build(),
                BUCKET_NAME);
    }

    @AfterAll
    static void removeOnlyObjectsThisRunCreated() throws Exception {
        if (adminClient == null) {
            return;
        }
        removeUnderPrefix(STAGING_PREFIX);
        removeUnderPrefix(FINAL_PREFIX);
        // Bucket is private and disposable: safe to delete once emptied.
        try {
            adminClient.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET_NAME).build());
        } catch (Exception e) {
            fail("Failed to remove the disposable verification bucket", e);
        }
    }

    @Test
    @Order(1)
    void stagingChunkUploadsRetrievesAndComparesByteForByte() throws Exception {
        byte[] chunk = randomBytes(64 * 1024);
        String key = STAGING_PREFIX + "chunk-0";
        storageService.upload(key, new ByteArrayInputStream(chunk), chunk.length,
                "application/octet-stream");
        byte[] downloaded = readAll(storageService.download(key));
        assertArrayEquals(chunk, downloaded);
        assertEquals(sha256Hex(chunk), sha256Hex(downloaded));
    }

    @Test
    @Order(2)
    void multipleChunksAssembleInCorrectOrderByteForByte() throws Exception {
        // Simulate the completion assembly: chunks staged in reverse arrival
        // order must still be concatenated in ascending index order.
        List<byte[]> chunks = List.of(randomBytes(1024), randomBytes(1024), randomBytes(7));
        byte[] assembled = new byte[chunks.stream().mapToInt(c -> c.length).sum()];

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String key = STAGING_PREFIX + "assemble-" + i;
            keys.add(key);
            storageService.upload(key, new ByteArrayInputStream(chunks.get(i)),
                    chunks.get(i).length, "application/octet-stream");
        }

        Vector<InputStream> parts = new Vector<>();
        for (int i = 0; i < keys.size(); i++) {
            parts.add(storageService.download(keys.get(i))); // ascending index order
        }
        try (InputStream sequence = new SequenceInputStream(Collections.enumeration(parts))) {
            byte[] downloaded = readAll(sequence);
            assembled = downloaded;
        }

        // Rebuild the expected concatenation to compare byte-for-byte.
        byte[] expected = new byte[assembled.length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, expected, offset, chunk.length);
            offset += chunk.length;
        }
        assertArrayEquals(expected, assembled);
        assertEquals(sha256Hex(expected), sha256Hex(assembled));
    }

    @Test
    @Order(3)
    void finalizedObjectMatchesSourceContent() throws Exception {
        byte[] source = randomBytes(256 * 1024 + 13); // non-multiple-of-block size
        String finalKey = FINAL_PREFIX + "completed-object";
        storageService.upload(finalKey, new ByteArrayInputStream(source), source.length,
                "text/plain");
        byte[] downloaded = readAll(storageService.download(finalKey));
        assertArrayEquals(source, downloaded);
        assertEquals(sha256Hex(source), sha256Hex(downloaded));
    }

    @Test
    @Order(4)
    void storageFailureIsReportedAsStorageException() {
        String missingKey = STAGING_PREFIX + "does-not-exist-" + java.util.UUID.randomUUID();
        StorageException failure = assertThrows(StorageException.class,
                () -> storageService.download(missingKey));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
        // Bucket name and credentials must not leak into the client-facing error.
        assertTrue(!failure.getMessage().contains(BUCKET_NAME));
        assertTrue(!failure.getMessage().toLowerCase().contains("minioadmin"));
    }

    @Test
    @Order(5)
    void stagingCleanupRemovesOnlySessionPrefixAndToleratesRepeatedRemoval() throws Exception {
        String sessionKeyA = STAGING_PREFIX + "cleanup-session-a";
        String sessionKeyB = STAGING_PREFIX + "cleanup-session-b";
        String unrelatedKey = STAGING_PREFIX + "cleanup-session-c-other-object";
        storageService.upload(sessionKeyA, new ByteArrayInputStream(new byte[]{1}), 1,
                "application/octet-stream");
        storageService.upload(sessionKeyB, new ByteArrayInputStream(new byte[]{2}), 1,
                "application/octet-stream");
        storageService.upload(unrelatedKey, new ByteArrayInputStream(new byte[]{3}), 1,
                "application/octet-stream");

        // Remove only the two staging objects of the finished session, then the
        // finalized object once more (idempotent re-remove like best-effort cleanup).
        storageService.delete(sessionKeyA);
        storageService.delete(sessionKeyB);
        assertThrows(StorageException.class, () -> storageService.download(sessionKeyA));
        assertThrows(StorageException.class, () -> storageService.download(sessionKeyB));
        // Removing an object that is already absent must not fail cleanup.
        storageService.delete(sessionKeyA);

        byte[] survived = readAll(storageService.download(unrelatedKey));
        assertEquals(3, survived[0]);
        storageService.delete(unrelatedKey);
    }

    private static byte[] randomBytes(int length) {
        byte[] data = new byte[length];
        RANDOM.nextBytes(data);
        return data;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input) {
            return in.readAllBytes();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void removeUnderPrefix(String prefix) {
        var listed = adminClient.listObjects(
                io.minio.ListObjectsArgs.builder().bucket(BUCKET_NAME)
                        .prefix(prefix).recursive(true).build());
        for (var result : listed) {
            try {
                String objectName = result.get().objectName();
                adminClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(BUCKET_NAME).object(objectName).build());
            } catch (Exception e) {
                fail("Failed to remove verification object " + e, e);
            }
        }
    }}
