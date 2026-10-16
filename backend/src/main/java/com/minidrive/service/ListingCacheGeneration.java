package com.minidrive.service;

import com.minidrive.config.CacheConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The shared, Redis-backed generation token for the {@link FileListingCache}
 * (Phase 10.5).
 *
 * <p>One opaque token namespace exists at a time, stored under a single Redis
 * key. Every listing request embeds the token it read into its cache key, and a
 * committed mutation replaces the token. A request that read an older token
 * can still finish and write under that older token, but no later request looks
 * there: the newer token produced a different key. This closes the
 * stale-repopulation race without deleting anything, and because the marker
 * lives in Redis it is shared by every application instance (never an
 * in-process counter).</p>
 *
 * <p>The token is a random UUID rather than an incrementing integer. Redis
 * runs with {@code allkeys-lru}, so the marker itself can be evicted; a counter
 * that restarted from zero (or from any value it had used before) could collide
 * with entries still alive under an earlier namespace, whereas a fresh random
 * token always opens a new namespace. A missing or evicted marker therefore
 * yields a cold cache, never a resurrected stale one.</p>
 *
 * <p>Initialization is atomic: the first writer wins via {@code SET NX} and any
 * loser re-reads the winner's token, so two instances starting at once agree on
 * one generation instead of racing to different ones.</p>
 *
 * <p>This component performs no caching and holds no business state; it only
 * reads and advances the token. Redis failures are allowed to propagate so the
 * caller can decide how to fail open.</p>
 */
@Component
public class ListingCacheGeneration {

    /**
     * The single Redis key holding the current generation token. It shares the
     * application cache prefix so cache operations and this marker live in the
     * same namespace, but it is not a {@link org.springframework.cache.Cache}
     * entry: it is read and written through {@link StringRedisTemplate} so it is
     * never wrapped by the JSON cache serializer.
     */
    public static final String GENERATION_KEY =
            CacheConfig.CACHE_KEY_PREFIX + FileListingCache.CACHE_NAME + ":generation";

    private static final Logger log = LoggerFactory.getLogger(ListingCacheGeneration.class);

    private final StringRedisTemplate redisTemplate;

    public ListingCacheGeneration(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns the current generation token, creating one atomically on first use
     * or after the marker was evicted. A newly created token is always distinct
     * from every token seen before, including one the caller may have read
     * earlier.
     */
    public String current() {

        String existing = redisTemplate.opsForValue().get(GENERATION_KEY);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        String candidate = newToken();
        Boolean created = redisTemplate.opsForValue().setIfAbsent(GENERATION_KEY, candidate);
        if (Boolean.TRUE.equals(created)) {
            log.debug("Initialized cache generation {} for {}", candidate, FileListingCache.CACHE_NAME);
            return candidate;
        }

        // Another instance initialized it between our GET and SET NX; adopt the
        // winner so both instances agree on the same generation.
        String winner = redisTemplate.opsForValue().get(GENERATION_KEY);
        return winner != null && !winner.isBlank() ? winner : candidate;
    }

    /**
     * Advances to a brand-new generation token, making every entry written under
     * the previous token unreachable. A single-key {@code SET} is atomic, so
     * concurrent advances simply leave one of the fresh tokens in place; both
     * are new namespaces, so either outcome is correct.
     */
    public void advance() {

        String token = newToken();
        redisTemplate.opsForValue().set(GENERATION_KEY, token);
        log.debug("Advanced cache generation to {} for {}", token, FileListingCache.CACHE_NAME);
    }

    /** Opaque, globally unique token that is never reused. */
    private static String newToken() {
        return UUID.randomUUID().toString();
    }
}
