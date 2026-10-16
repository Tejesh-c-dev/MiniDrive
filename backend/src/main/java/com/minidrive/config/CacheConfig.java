package com.minidrive.config;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;

/**
 * Redis cache infrastructure (Phase 10.2).
 *
 * <p>Cache-only component: Redis is never the source of truth, so a cache miss
 * must always be recoverable from PostgreSQL and an unavailable Redis must
 * surface as an error from whatever operation touches the cache, never be
 * silently substituted with an in-memory fallback.</p>
 *
 * <p>Values are stored as explicit JSON through
 * {@link GenericJacksonJsonRedisSerializer} (Jackson 3, the same mapper family
 * the rest of Boot 4.1 uses). Java native serialization is deliberately not
 * used, mirroring Boot's warning that its own Redis cache default (JDK
 * serialization) is not the recommended production choice. Class hints embedded
 * in the JSON are constrained by a conservative allowlist, so only project
 * types and standard library value types can ever be reconstituted.</p>
 *
 * <p>This class intentionally contains no business caching. Applying
 * {@code @Cacheable}/{@code @CacheEvict} to services belongs to later Phase 10
 * steps; this configuration only provides the cache manager they will bind to.
 * It is kept separate from {@link com.minidrive.MiniDriveApplication} so the
 * application class does not carry cache wiring details.</p>
 *
 * <p>Verified value shapes: DTO/record roots, enums, scalars and mutable
 * collection roots (ArrayList/HashMap) round-trip. Root-level immutable
 * collections ({@code List.of}/{@code Map.of}) lose their root type id on the
 * Spring Data Redis 4.1.1 / Jackson 3.1.5 write path independently of this
 * configuration, so later phases must cache only the verified shapes.</p>
 */
@Configuration
public class CacheConfig {

    /** Stable application-specific key prefix applied before each cache name. */
    public static final String CACHE_KEY_PREFIX = "minidrive:cache:";

    /**
     * Default entry TTL. Short by design: staged invalidation semantics are
     * introduced together with actual cached operations, so this only bounds
     * staleness until then.
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * Object serializer for cache values. Polymorphic class hints are written
     * into the JSON and validated on the way back in through the allowlist, so
     * round-trips return concrete DTO/enum types instead of maps.
     */
    @Bean
    public GenericJacksonJsonRedisSerializer redisJsonSerializer() {

        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType((context, type) -> {
                    String name = type.getName();
                    return name.startsWith("com.minidrive.")
                            || name.startsWith("java.util.")
                            || name.startsWith("java.time.")
                            || name.startsWith("java.lang.");
                })
                .build();

        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
    }

    /**
     * The defaults every cache inherits: JSON values, the application key
     * prefix, the default TTL and null values disabled. Exposed as a static
     * factory so tests can assert the exact configuration without a Redis
     * connection, which the cache manager itself never needs to be built.
     */
    static RedisCacheConfiguration cacheDefaults(
            GenericJacksonJsonRedisSerializer serializer) {

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_CACHE_TTL)
                .disableCachingNullValues()
                .prefixCacheNameWith(CACHE_KEY_PREFIX)
                .serializeValuesWith(
                        SerializationPair.fromSerializer(serializer));
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            GenericJacksonJsonRedisSerializer redisJsonSerializer) {

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheDefaults(redisJsonSerializer))
                .build();
    }
}
