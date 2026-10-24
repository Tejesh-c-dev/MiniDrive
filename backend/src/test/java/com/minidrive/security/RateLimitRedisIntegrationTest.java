package com.minidrive.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Optional actual-Redis test. Uses unique identities and only its own expiring keys. */
class RateLimitRedisIntegrationTest {
    private LettuceConnectionFactory factory;
    @AfterEach void close() { if (factory != null) factory.destroy(); }

    private boolean reachable() {
        try (Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("REDIS_HOST","localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","56379"))),1000);
            return true;
        } catch (Exception e) { return false; }
    }

    @Test void concurrentInstancesShareAtomicLimitAndRedisExpiresCounter() throws Exception {
        if (!reachable()) throw new TestAbortedException("Redis is unavailable at configured endpoint");
        factory=new LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST","localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","56379")));
        factory.afterPropertiesSet();
        StringRedisTemplate template=new StringRedisTemplate(factory);
        RateLimitService first=service(template), second=service(template);
        String id="rate-limit-it-"+java.util.UUID.randomUUID();
        int total=40, allowed=7;
        var pool=Executors.newFixedThreadPool(12);
        CountDownLatch start=new CountDownLatch(1);
        AtomicInteger successes=new AtomicInteger(), limited=new AtomicInteger();
        for (int i=0;i<total;i++) {
            final int n=i;
            pool.submit(() -> {
                try { start.await(); (n%2==0?first:second).registration(id); successes.incrementAndGet(); }
                catch (RateLimitExceededException expected) { limited.incrementAndGet(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        start.countDown(); pool.shutdown();
        while (!pool.isTerminated()) Thread.sleep(10);
        assertEquals(allowed,successes.get());
        assertEquals(total-allowed,limited.get());
        String key="minidrive:ratelimit:registration:"+java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Long ttl=template.getExpire(key);
        org.junit.jupiter.api.Assertions.assertNotNull(ttl);
        org.junit.jupiter.api.Assertions.assertTrue(ttl > 0 && ttl <= 30);
        template.delete(key);
    }

    @Test void unreachableRedisEndpointFailsClosed() {
        factory=new LettuceConnectionFactory("127.0.0.1",1);
        factory.afterPropertiesSet();
        StringRedisTemplate unavailable=new StringRedisTemplate(factory);
        unavailable.afterPropertiesSet();
        RateLimitService limiter=service(unavailable);
        org.junit.jupiter.api.Assertions.assertThrows(RateLimitUnavailableException.class,
                () -> limiter.registration("controlled-redis-outage-"+java.util.UUID.randomUUID()));
    }

    private RateLimitService service(StringRedisTemplate template) {
        return new RateLimitService(template,false,List.of(),7,7,7,7,
                Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(30));
    }
}
