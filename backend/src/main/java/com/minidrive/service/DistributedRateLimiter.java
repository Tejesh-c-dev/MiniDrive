package com.minidrive.service;

import com.minidrive.config.RateLimitProperties;
import com.minidrive.config.RateLimitProperties.Bucket;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class DistributedRateLimiter {
    private static final String SCRIPT = """
            local max_ttl = 1
            local blocked = false
            for i, key in ipairs(KEYS) do
              local count = tonumber(redis.call('GET', key) or '0')
              local limit = tonumber(ARGV[(i - 1) * 2 + 1])
              if count >= limit then
                blocked = true
                local ttl = redis.call('PTTL', key)
                if ttl > max_ttl then max_ttl = ttl end
              end
            end
            if blocked then return -max_ttl end
            for i, key in ipairs(KEYS) do
              local count = redis.call('INCR', key)
              if count == 1 then
                redis.call('PEXPIRE', key, ARGV[(i - 1) * 2 + 2])
              end
            end
            return 0
            """;
    private static final DefaultRedisScript<Long> REDIS_SCRIPT = new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public DistributedRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void login(String ip, String email) {
        enforce("login", ip, properties.getLoginIp(), "account", normalizeAccount(email), properties.getLoginAccount());
    }

    public void register(String ip, String email) {
        enforce("register", ip, properties.getRegisterIp(), "account", normalizeAccount(email), properties.getRegisterAccount());
    }

    public void share(String ip, String token) {
        enforce("share", ip, properties.getShareIp(), "token", digest(token), properties.getShareToken());
    }

    private void enforce(String route, String firstIdentity, Bucket firstBucket,
                         String secondKind, String secondIdentity, Bucket secondBucket) {
        String firstKey = key(route, "ip", digest(firstIdentity));
        String secondKey = key(route, secondKind, secondIdentity);
        try {
            Long retryMillis = redis.execute(REDIS_SCRIPT, List.of(firstKey, secondKey),
                    Integer.toString(firstBucket.getLimit()), Long.toString(windowMillis(firstBucket)),
                    Integer.toString(secondBucket.getLimit()), Long.toString(windowMillis(secondBucket)));
            if (retryMillis == null) throw new IllegalStateException("Redis returned no rate-limit result");
            if (retryMillis < 0) {
                throw new RateLimitExceededException(((-retryMillis) + 999) / 1000);
            }
        } catch (RateLimitExceededException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RateLimitUnavailableException(ex);
        }
    }

    private static long windowMillis(Bucket bucket) {
        Duration window = bucket.getWindow();
        return Math.max(1, window.toMillis());
    }

    private static String key(String route, String type, String identity) {
        return "minidrive:rate-limit:v1:" + route + ":" + type + ":" + identity;
    }

    private static String normalizeAccount(String account) {
        return digest(account == null ? "" : account.trim().toLowerCase(Locale.ROOT));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
