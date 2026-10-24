package com.minidrive.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RateLimitServiceTest {
    private RateLimitService limiter(StringRedisTemplate redis, int limit) {
        return new RateLimitService(redis, false, List.of(), limit, limit, limit, limit,
                Duration.ofSeconds(40), Duration.ofSeconds(30), Duration.ofSeconds(20));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void permitsAtLimitAndRejectsNextWithRetryWindow() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L).thenReturn(2L);
        RateLimitService service=limiter(redis,1);
        service.registration("192.0.2.1");
        RateLimitExceededException ex=assertThrows(RateLimitExceededException.class,
                () -> service.registration("192.0.2.1"));
        assertEquals(40, ex.getRetryAfter());
        verify(redis,times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void storeFailureFailsClosed() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis offline"));
        assertThrows(RateLimitUnavailableException.class, () -> limiter(redis,1).share("192.0.2.2"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void accountIdentityIsNormalizedAndScopedToIp() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RateLimitService service=limiter(redis,10);
        service.loginAccount("192.0.2.1", " User@Example.com ");
        service.loginAccount("192.0.2.1", "user@example.com");
        service.loginAccount("192.0.2.2", "user@example.com");
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis,times(3)).execute(any(RedisScript.class), captor.capture(), any(Object[].class));
        assertEquals(captor.getAllValues().get(0),captor.getAllValues().get(1));
        assertNotEquals(captor.getAllValues().get(1),captor.getAllValues().get(2));
        assertFalse(captor.getAllValues().get(0).toString().contains("user@example.com"));
    }

    @Test void forwardedAddressIsIgnoredUnlessRemoteProxyIsExplicitlyTrusted() {
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.8");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        var untrusted=new RateLimitService(mock(StringRedisTemplate.class), false, List.of("198.51.100.8"),
                1,1,1,1,Duration.ofSeconds(5),Duration.ofSeconds(5),Duration.ofSeconds(5));
        assertEquals("198.51.100.8", untrusted.clientIp(request));
        var trusted=new RateLimitService(mock(StringRedisTemplate.class), true, List.of("198.51.100.0/24"),
                1,1,1,1,Duration.ofSeconds(5),Duration.ofSeconds(5),Duration.ofSeconds(5));
        assertEquals("203.0.113.1", trusted.clientIp(request));
    }
}
