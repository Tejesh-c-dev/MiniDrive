package com.minidrive.cache;

import com.minidrive.service.ListingCacheGeneration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the Phase 10.5 shared generation token
 * ({@link ListingCacheGeneration}): atomic first-use initialization, agreement
 * between two instances that share one Redis, a losing {@code SET NX}
 * re-reading the winner, and an evicted marker opening a brand-new namespace
 * instead of recycling an earlier token.
 *
 * <p>No Redis server is required: a single {@link Map} stands in for the shared
 * server, and every store built by {@link #newInstance()} reads and writes that
 * same map, so two stores genuinely model two application instances talking to
 * one Redis.</p>
 */
class ListingCacheGenerationTest {

    /** The shared "Redis" both instances read and write. */
    private final Map<String, String> sharedRedis = new HashMap<>();

    @BeforeEach
    void setUp() {
        sharedRedis.clear();
    }

    @Test
    void currentInitializesAnOpaqueTokenOnceAndReusesIt() {
        ListingCacheGeneration store = newInstance();

        String token = store.current();

        // Opaque and unique: a UUID, never a reusable integer counter.
        assertThat(UUID.fromString(token)).isNotNull();
        assertThat(sharedRedis).containsEntry(ListingCacheGeneration.GENERATION_KEY, token);
        assertThat(store.current()).isEqualTo(token);
    }

    @Test
    void twoInstancesSharingRedisAgreeOnOneGeneration() {
        ListingCacheGeneration instanceA = newInstance();
        ListingCacheGeneration instanceB = newInstance();

        String tokenA = instanceA.current();
        String tokenB = instanceB.current();

        assertThat(tokenB).isEqualTo(tokenA);
        assertThat(sharedRedis).hasSize(1);
    }

    @Test
    void anAdvanceByOneInstanceIsImmediatelyVisibleToAnother() {
        ListingCacheGeneration instanceA = newInstance();
        ListingCacheGeneration instanceB = newInstance();

        String before = instanceB.current();
        instanceA.advance();

        String after = instanceB.current();
        assertThat(after).isNotEqualTo(before);
        assertThat(sharedRedis).containsEntry(ListingCacheGeneration.GENERATION_KEY, after);
    }

    @Test
    void anEvictedMarkerOpensANewNamespaceInsteadOfRecyclingTheOldToken() {
        ListingCacheGeneration store = newInstance();

        String before = store.current();
        sharedRedis.clear(); // allkeys-lru evicted the marker

        String after = store.current();
        assertThat(after).isNotEqualTo(before);
        assertThat(UUID.fromString(after)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLosingAtomicInitializationAdoptsTheWinnersToken() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(template.opsForValue()).willReturn(values);

        // The first read sees no marker; a competing writer creates one before
        // this instance's SET NX lands, so the SET NX loses.
        AtomicInteger reads = new AtomicInteger();
        when(values.get(any())).thenAnswer(invocation ->
                reads.getAndIncrement() == 0
                        ? null
                        : sharedRedis.get(invocation.getArgument(0)));
        when(values.setIfAbsent(any(), any())).thenAnswer(invocation -> {
            sharedRedis.put(invocation.getArgument(0), "winner-token");
            return false;
        });

        assertThat(new ListingCacheGeneration(template).current()).isEqualTo("winner-token");
    }

    /** A store wired to the shared in-memory "Redis" map. */
    @SuppressWarnings("unchecked")
    private ListingCacheGeneration newInstance() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(template.opsForValue()).willReturn(values);

        when(values.get(any())).thenAnswer(invocation ->
                sharedRedis.get(invocation.getArgument(0)));
        when(values.setIfAbsent(any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (sharedRedis.containsKey(key)) {
                return false;
            }
            sharedRedis.put(key, invocation.getArgument(1));
            return true;
        });
        doAnswer(invocation -> {
            sharedRedis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(any(), any());

        return new ListingCacheGeneration(template);
    }
}
