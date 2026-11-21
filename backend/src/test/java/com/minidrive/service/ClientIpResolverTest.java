package com.minidrive.service;

import com.minidrive.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    @Test
    void ignoresForwardedIpFromUntrustedPeer() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @Test
    void resolvesClientThroughConfiguredProxyChain() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "198.51.100.33, 10.0.0.7");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.33");
    }

    @Test
    void malformedForwardedChainFallsBackToTrustedPeer() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "attacker.example");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.8");
    }
}
