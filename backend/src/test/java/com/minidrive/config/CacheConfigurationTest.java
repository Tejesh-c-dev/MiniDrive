package com.minidrive.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for the Phase 10.2 cache configuration itself: JSON (never
 * Java-native) value serialization with round-trip fidelity, the stable
 * application key prefix, the 60-second default TTL, and null caching being
 * disabled. No Redis server is required; assertions run against the
 * configuration objects built by {@link CacheConfig}.
 */
class CacheConfigurationTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY = "key1";
    private static final String CACHE_NAME = "files";

    /** Sample value shaped like the DTO family the cache will hold. */
    record SampleValue(String name, int currentVersion, LocalDateTime updatedAt) { }

    private GenericJacksonJsonRedisSerializer serializer;
    private RedisCacheConfiguration cacheDefaults;

    @BeforeEach
    void setUp() {
        CacheConfig cacheConfig = new CacheConfig();
        serializer = cacheConfig.redisJsonSerializer();
        cacheDefaults = CacheConfig.cacheDefaults(serializer);
    }

    @Test
    void serializationIsJsonNotJavaNativeSerialization() {
        byte[] bytes = serializer.serialize(new SampleValue("report.pdf", 3, LocalDateTime.now()));

        assertThat(bytes).isNotEmpty();
        // Java native serialization always starts with 0xAC 0xED; JSON starts with '{'.
        assertThat(bytes[0]).isEqualTo((byte) '{');
    }

    @Test
    void roundTripRestoresConcreteTypeAndValue() {
        LocalDateTime when = LocalDateTime.of(2026, 10, 15, 12, 0);
        SampleValue original = new SampleValue("report.pdf", 3, when);

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isEqualTo(original);
        // Without effective type hints a generic mapper would return a
        // LinkedHashMap instead of the sampled record type.
        assertThat(restored).isInstanceOf(SampleValue.class);
    }

    @Test
    void roundTripPreservesMutableNestedAndStandardLibraryTypes() {
        Map<String, List<SampleValue>> original = new java.util.HashMap<>();
        original.put("root", new java.util.ArrayList<>(
                List.of(new SampleValue("a.txt", 1, LocalDateTime.MIN))));

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isEqualTo(original);
    }

    /*
     * Documented Spring Data Redis 4.1.1 / Jackson 3.1.5 limitation, not a
     * configuration bug: root-level immutable collections lose the root type
     * id on the write path even with default typing (or even unsafe typing)
     * enabled, so deserialize cannot reconstitute them. The cache layer's
     * supported contract therefore excludes List.of/Map.of roots. This
     * sentinel asserts the current behavior; if an upgrade fixes root typing,
     * this test should be turned into a positive round-trip.
     */
    @Test
    void immutableRootCollectionsAreNotRoundTrippableWithThisLibrary() {
        List<SampleValue> original = List.of(new SampleValue("a.txt", 1, LocalDateTime.MIN));

        byte[] bytes = serializer.serialize(original);

        assertThatThrownBy(() -> serializer.deserialize(bytes))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void scalarRootsRoundTripWithoutTypeIdRequirements() {
        assertThat(serializer.deserialize(serializer.serialize("VIEWER")))
                .isEqualTo("VIEWER");
        assertThat(serializer.deserialize(serializer.serialize(42))).isEqualTo(42);
    }

    @Test
    void deserializationRejectsTypesOutsideTheAllowlist() {
        // A payload carrying a class hint outside the allowed packages must not
        // be reconstituted: the PolymorphicTypeValidator rejects it instead.
        String hostile = "{\"@class\":\"java.lang.Runtime\"}";

        assertThatThrownBy(() -> serializer.deserialize(hostile.getBytes()))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void cacheKeysArePrefixedWithStableApplicationPrefix() {
        String expected = CacheConfig.CACHE_KEY_PREFIX + CACHE_NAME + "::" + KEY;

        assertThat(cacheDefaults.getKeyPrefixFor(CACHE_NAME) + KEY)
                .isEqualTo(expected);
        assertThat(cacheDefaults.usePrefix()).isTrue();
    }

    @Test
    void nullValuesAreNotCacheable() {
        assertThat(cacheDefaults.getAllowCacheNullValues()).isFalse();
    }

    @Test
    void defaultTtlIsSixtySeconds() {
        assertThat(cacheDefaults.getTtlFunction().getTimeToLive(KEY, "value"))
                .isEqualTo(TTL);
        assertThat(CacheConfig.DEFAULT_CACHE_TTL).isEqualTo(TTL);
        assertThat(CacheConfig.CACHE_KEY_PREFIX).isEqualTo("minidrive:cache:");
    }

    @Test
    void cacheManagerIsBuiltWithThoseDefaults() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory("localhost", 56379);
        try {
            connectionFactory.afterPropertiesSet();
            RedisCacheManager manager = (RedisCacheManager)
                    new CacheConfig().cacheManager(connectionFactory, serializer);

            org.springframework.data.redis.cache.RedisCache cache =
                    (org.springframework.data.redis.cache.RedisCache)
                            manager.getCache(CACHE_NAME);
            assertThat(cache.getCacheConfiguration().getKeyPrefixFor(CACHE_NAME))
                    .isEqualTo(CacheConfig.CACHE_KEY_PREFIX + CACHE_NAME + "::");
        } finally {
            connectionFactory.destroy();
        }
    }
}
