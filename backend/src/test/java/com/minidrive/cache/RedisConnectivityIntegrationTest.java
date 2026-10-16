package com.minidrive.cache;

import com.minidrive.config.CacheConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-server Redis integration test (Phase 10.2).
 *
 * <p>Requires an actual Redis server at the address the backend is configured
 * for: the Compose stack publishes one on {@code 127.0.0.1:56379} and the
 * {@code REDIS_HOST}/{@code REDIS_PORT} environment variables override it.
 * When no server answers, every test in this class is a genuine
 * {@code Skipped} outcome - connectivity is never faked through an embedded or
 * in-memory substitute, and skips are reported as skips.</p>
 *
 * <p>No Spring context is started: this exercises the exact client,
 * serializer and key/TTL configuration Phase 10.2 wired up, directly through
 * Spring Data Redis.</p>
 */
class RedisConnectivityIntegrationTest {

    private static final String HOST =
            System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT =
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "56379"));

    /** Sample value shaped like the DTO family the cache will hold. */
    record SampleValue(String name, int currentVersion, LocalDateTime updatedAt) { }

    private LettuceConnectionFactory connectionFactory;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
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

    private void assumeServerReachable() {
        if (!serverReachable()) {
            throw new org.opentest4j.TestAbortedException(
                    "No Redis server reachable at " + HOST + ":" + PORT
                            + " (start the Compose stack: docker compose -f infra/docker-compose.yml up -d redis)");
        }
    }

    private RedisConnectionFactory factory() {
        connectionFactory = new LettuceConnectionFactory(HOST, PORT);
        connectionFactory.afterPropertiesSet();
        return connectionFactory;
    }

    @Test
    void pingsRealServer() {
        assumeServerReachable();

        try (var connection = factory().getConnection()) {
            String pong = connection.ping();
            assertThat(pong).isEqualTo("PONG");
        }
    }

    @Test
    void storesAndReadsBackJsonUnderTheConfiguredPrefixWithTtl() {
        assumeServerReachable();

        GenericJacksonJsonRedisSerializer serializer =
                new CacheConfig().redisJsonSerializer();
        String key = CacheConfig.CACHE_KEY_PREFIX + "files::connectivity-test";
        SampleValue value = new SampleValue(
                "connectivity.txt", 1, LocalDateTime.of(2026, 10, 15, 12, 0));

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory());
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        try {
            template.opsForValue().set(key, value, Duration.ofSeconds(60));

            Object readBack = template.opsForValue().get(key);
            assertThat(readBack).isEqualTo(value);
            assertThat(readBack).isInstanceOf(SampleValue.class);

            Long ttl = template.getExpire(key);
            assertThat(ttl).isNotNull();
            // Ambient delay between set and read must leave ~60s on the clock.
            assertThat(ttl).isBetween(55L, 60L);

            // Writes through the cache-layer naming convention only; no other
            // application keys were touched by this test.
            assertThat(template.keys(CacheConfig.CACHE_KEY_PREFIX + "*"))
                    .contains(key);
        } finally {
            template.delete(key);
        }
    }
}
