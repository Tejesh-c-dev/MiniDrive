package com.minidrive.service;

import com.minidrive.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DistributedRateLimiterRedisIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "56379"));
    @Autowired private StringRedisTemplate template;
    private DistributedRateLimiter limiter;

    @BeforeEach
    void setUp() {
        if (!redisReachable()) throw new TestAbortedException("Redis unavailable at " + HOST + ":" + PORT);
        limiter = new DistributedRateLimiter(template, properties(5, Duration.ofSeconds(2)));
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void enforcesLoginRegistrationAndShareIdentityLimitsWithNormalizedKeys() {
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) limiter.login("192.0.2." + i, " User" + suffix + "@Example.com ");
        assertThatThrownBy(() -> limiter.login("198.51.100.1", "user" + suffix + "@example.com"))
                .isInstanceOf(RateLimitExceededException.class);

        for (int i = 0; i < 5; i++) limiter.register("203.0.113." + i, "reg" + suffix + "@example.com");
        assertThatThrownBy(() -> limiter.register("198.51.100.2", "REG" + suffix + "@example.com"))
                .isInstanceOf(RateLimitExceededException.class);

        for (int i = 0; i < 5; i++) limiter.share("192.0.2." + i, "token-" + suffix);
        assertThatThrownBy(() -> limiter.share("198.51.100.2", "token-" + suffix))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void isolatesIpsAndSharesAtomicStateAcrossLimiterInstances() {
        String suffix = UUID.randomUUID().toString();
        DistributedRateLimiter secondInstance = new DistributedRateLimiter(template, properties(5, Duration.ofSeconds(2)));
        String limitedIp = "198.18.0.1";
        for (int i = 0; i < 5; i++) limiter.login(limitedIp, "different-" + i + "-" + suffix + "@example.com");
        assertThatThrownBy(() -> secondInstance.login(limitedIp, "another-" + suffix + "@example.com"))
                .isInstanceOf(RateLimitExceededException.class);
        limiter.login("198.18.0.6", "fresh-" + suffix + "@example.com");
    }

    @Test
    void concurrentRequestsCannotExceedLimit() throws Exception {
        String ip = "198.19." + Math.abs(UUID.randomUUID().hashCode() % 200) + ".1";
        String suffix = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int request = i;
                requests.add(() -> {
                    try {
                        limiter.login(ip, "concurrent-" + suffix + "-" + request + "@example.com");
                        return true;
                    } catch (RateLimitExceededException rejected) {
                        return false;
                    }
                });
            }
            long accepted = pool.invokeAll(requests).stream().filter(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).count();
            assertThat(accepted).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bucketRecoversAfterExpiry() throws InterruptedException {
        String ip = "198.20." + Math.abs(UUID.randomUUID().hashCode() % 200) + ".1";
        String account = "expiry-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) limiter.login(ip, account);
        assertThatThrownBy(() -> limiter.login(ip, account)).isInstanceOf(RateLimitExceededException.class);
        Thread.sleep(2200);
        limiter.login(ip, account);
    }

    @Test
    void redisUnavailableFailsClosed() {
        LettuceConnectionFactory unavailableFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        unavailableFactory.afterPropertiesSet();
        try {
            StringRedisTemplate unavailable = new StringRedisTemplate(unavailableFactory);
            unavailable.afterPropertiesSet();
            DistributedRateLimiter unavailableLimiter = new DistributedRateLimiter(unavailable, properties(5, Duration.ofMinutes(1)));
            assertThatThrownBy(() -> unavailableLimiter.login("192.0.2.55", "outage@example.com"))
                    .isInstanceOf(RateLimitUnavailableException.class);
        } finally {
            unavailableFactory.destroy();
        }
    }

    private static RateLimitProperties properties(int limit, Duration window) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginIp(new RateLimitProperties.Bucket(limit, window));
        properties.setLoginAccount(new RateLimitProperties.Bucket(limit, window));
        properties.setRegisterIp(new RateLimitProperties.Bucket(limit, window));
        properties.setRegisterAccount(new RateLimitProperties.Bucket(limit, window));
        properties.setShareIp(new RateLimitProperties.Bucket(limit, window));
        properties.setShareToken(new RateLimitProperties.Bucket(limit, window));
        return properties;
    }

    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception ignored) { return false; }
    }
}
