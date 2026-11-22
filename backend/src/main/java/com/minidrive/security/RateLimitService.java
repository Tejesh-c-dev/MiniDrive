package com.minidrive.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;

/** Redis fixed-window limiter. One Lua operation increments and sets expiry atomically. */
@Service
public class RateLimitService {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final boolean trustProxy;
    private final List<String> trustedProxies;
    private final int registrationLimit;
    private final int loginIpLimit;
    private final int loginAccountLimit;
    private final int shareLimit;
    private final Duration registrationWindow;
    private final Duration loginWindow;
    private final Duration shareWindow;

    public RateLimitService(StringRedisTemplate redis,
            @Value("${minidrive.rate-limit.trust-proxy:false}") boolean trustProxy,
            @Value("${minidrive.rate-limit.trusted-proxies:}") List<String> trustedProxies,
            @Value("${minidrive.rate-limit.registration.limit:20}") int registrationLimit,
            @Value("${minidrive.rate-limit.login.ip-limit:60}") int loginIpLimit,
            @Value("${minidrive.rate-limit.login.account-limit:12}") int loginAccountLimit,
            @Value("${minidrive.rate-limit.share.limit:120}") int shareLimit,
            @Value("${minidrive.rate-limit.registration.window:1h}") Duration registrationWindow,
            @Value("${minidrive.rate-limit.login.window:15m}") Duration loginWindow,
            @Value("${minidrive.rate-limit.share.window:1m}") Duration shareWindow) {
        this.redis=redis; this.trustProxy=trustProxy; this.trustedProxies=trustedProxies;
        this.registrationLimit=positiveLimit(registrationLimit); this.loginIpLimit=positiveLimit(loginIpLimit);
        this.loginAccountLimit=positiveLimit(loginAccountLimit); this.shareLimit=positiveLimit(shareLimit);
        this.registrationWindow=boundedWindow(registrationWindow);
        this.loginWindow=boundedWindow(loginWindow);
        this.shareWindow=boundedWindow(shareWindow);
    }

    public void registration(String ip) { check("registration", ip, registrationLimit, registrationWindow); }
    public void loginIp(String ip) { check("login:ip", ip, loginIpLimit, loginWindow); }
    public void loginAccount(String ip, String email) {
        // Account bucket is scoped to source IP: knowledge of an email cannot lock out the victim.
        check("login:account", ip + "\n" + email.trim().toLowerCase(java.util.Locale.ROOT), loginAccountLimit, loginWindow);
    }
    public void share(String ip) { check("share", ip, shareLimit, shareWindow); }
    public int retryAfter(String policy) {
        Duration d = switch (policy) { case "registration" -> registrationWindow; case "share" -> shareWindow; default -> loginWindow; };
        return (int)Math.max(1, Math.min(86400, d.toSeconds()));
    }

    public String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        if (trustProxy && trustedProxies.stream().filter(s -> !s.isBlank()).anyMatch(cidr -> new IpAddressMatcher(cidr.trim()).matches(remote))) {
            String forwarded=request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Trusted ingress proxies must append the address they observed. Use
                // the rightmost value so a client-supplied leftmost XFF value is ignored.
                String[] chain=forwarded.split(",");
                String candidate=chain[chain.length-1].trim();
                if (candidate.matches("[0-9a-fA-F:.]+")) return candidate;
            }
        }
        return remote;
    }

    private void check(String policy, String identity, int limit, Duration window) {
        try {
            String key="minidrive:ratelimit:"+policy+":"+sha256(identity);
            Long count=redis.execute(INCREMENT, List.of(key), Long.toString(window.toMillis()));
            if (count == null) throw new IllegalStateException("No Redis rate-limit result");
            if (count > limit) throw new RateLimitExceededException(retryAfter(policy));
        } catch (RateLimitExceededException e) { throw e; }
        catch (RuntimeException e) { throw new RateLimitUnavailableException(e); }
    }
    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static int positiveLimit(int value) {
        if (value < 1) throw new IllegalArgumentException("Rate-limit values must be positive");
        return value;
    }
    private static Duration boundedWindow(Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Rate-limit windows must be positive and at most 24 hours");
        return value;
    }
}
