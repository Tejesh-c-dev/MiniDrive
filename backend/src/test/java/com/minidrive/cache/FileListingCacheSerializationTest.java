package com.minidrive.cache;

import com.minidrive.config.CacheConfig;
import com.minidrive.dto.file.FileResponse;

import org.junit.jupiter.api.Test;

import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused test for the cached value's serialization contract (Phase 10.3).
 *
 * <p>The listing cache stores the DTO list the endpoint returns, so the value
 * must survive a round-trip through the configuration Phase 10.2 wired up
 * ({@link CacheConfig#redisJsonSerializer()}) with every {@link FileResponse}
 * field intact. No Redis server is required: the assertions run against the
 * serializer directly, exactly as the Redis cache would use it.</p>
 */
class FileListingCacheSerializationTest {

    private final GenericJacksonJsonRedisSerializer serializer =
            new CacheConfig().redisJsonSerializer();

    private static FileResponse file(String name, Long sizeBytes) {
        return new FileResponse(
                UUID.randomUUID(),
                name,
                null,
                "text/plain",
                sizeBytes,
                LocalDateTime.of(2026, 10, 15, 12, 0),
                2);
    }

    @Test
    void cachedListingRoundTripsWithEveryDtoFieldIntact() {
        List<FileResponse> original = new ArrayList<>(
                List.of(file("a.txt", 10L), file("b.txt", 20L)));

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isInstanceOf(List.class);
        assertThat((List<?>) restored).hasSize(2);
        assertThat((List<?>) restored).allSatisfy(element ->
                assertThat(element).isInstanceOf(FileResponse.class));

        List<FileResponse> restoredFiles = ((List<?>) restored).stream()
                .map(FileResponse.class::cast)
                .toList();

        for (int index = 0; index < original.size(); index++) {
            FileResponse expected = original.get(index);
            FileResponse actual = restoredFiles.get(index);
            assertThat(actual.getId()).isEqualTo(expected.getId());
            assertThat(actual.getName()).isEqualTo(expected.getName());
            assertThat(actual.getFolderId()).isEqualTo(expected.getFolderId());
            assertThat(actual.getContentType()).isEqualTo(expected.getContentType());
            assertThat(actual.getSizeBytes()).isEqualTo(expected.getSizeBytes());
            assertThat(actual.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
            assertThat(actual.getCurrentVersion()).isEqualTo(expected.getCurrentVersion());
        }
    }

    @Test
    void storedJsonCarriesExactlyTheListingDtoFields() {
        String json = new String(
                serializer.serialize(new ArrayList<>(List.of(file("a.txt", 10L)))),
                StandardCharsets.UTF_8);

        // The endpoint's response shape, no more and no less: the stored value
        // is the same DTO family the controller serializes.
        assertThat(json).contains(
                "\"id\"",
                "\"name\"",
                "\"folderId\"",
                "\"contentType\"",
                "\"sizeBytes\"",
                "\"updatedAt\"",
                "\"currentVersion\"");
        assertThat(json).doesNotContain("\"objectKey\"", "\"checksum\"", "\"ownerId\"");

        // The cache never needs the entity's storage details, and the value is
        // stored as JSON rather than Java-native serialization.
        assertThat(json.charAt(0)).isNotEqualTo((char) 0xAC);
        assertThat(json).contains("a.txt").contains("text/plain");
    }

    /**
     * Pins the reason the cache component copies its summaries into a mutable
     * {@code ArrayList}: a root-level immutable collection cannot be
     * reconstituted by Spring Data Redis 4.1.1 / Jackson 3.1.5, so caching
     * {@code List.of(...)}/receiving it back would always fail and force a
     * database read.
     */
    @Test
    void immutableRootListingIsNotRoundTrippableSoTheCacheUsesAMutableRoot() {
        List<FileResponse> immutableRoot = List.of(file("a.txt", 10L));

        byte[] bytes = serializer.serialize(immutableRoot);

        assertThatThrownBy(() -> serializer.deserialize(bytes))
                .isInstanceOf(SerializationException.class);
    }
}
