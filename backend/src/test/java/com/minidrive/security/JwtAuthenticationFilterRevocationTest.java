package com.minidrive.security;

import com.minidrive.service.TokenRevocationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterRevocationTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revocationStoreFailureLeavesRequestUnauthenticated() throws Exception {
        JwtService jwt = mock(JwtService.class);
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        TokenRevocationService revocations = mock(TokenRevocationService.class);
        JwtService.ValidatedToken token = new JwtService.ValidatedToken("user@test.com", "jti-1", Instant.now().plusSeconds(60));
        when(jwt.parseValidatedToken("signed-token")).thenReturn(token);
        doThrow(new IllegalStateException("database unavailable")).when(revocations).isRevoked("jti-1");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, users, revocations);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("previous-dispatch", null, List.of()));

        filter.doFilter(request, response, chain);

        verify(revocations).isRevoked("jti-1");
        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(response.getStatus() < 400, "the filter remains composable; protected-route authorization rejects anonymous access");
    }
}
