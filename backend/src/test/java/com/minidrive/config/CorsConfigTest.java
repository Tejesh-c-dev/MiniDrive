package com.minidrive.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    @Test
    void acceptsOneOrMultipleConfiguredExactOrigins() {
        assertEquals(List.of("https://drive.example.test"),
                CorsConfig.parseOrigins("https://drive.example.test"));
        assertEquals(List.of("https://drive.example.test", "https://admin.example.test:8443"),
                CorsConfig.parseOrigins("https://drive.example.test, https://admin.example.test:8443"));
    }

    @Test
    void rejectsMissingMalformedWildcardAndPathOrigins() {
        for (String invalid : List.of("", "https://ok.example.test,", "*",
                "https://*.example.test", "https://example.test/path", "ftp://example.test")) {
            assertThrows(IllegalArgumentException.class, () -> CorsConfig.parseOrigins(invalid), invalid);
        }
    }

    @Test
    void policyUsesExactOriginsAndNarrowMethodsHeadersWithoutCredentials() {
        CorsConfigurationSource source = new CorsConfig(
                "https://drive.example.test,http://localhost:5173").corsConfigurationSource();
        CorsConfiguration policy = source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/files"));

        assertEquals(List.of("https://drive.example.test", "http://localhost:5173"), policy.getAllowedOrigins());
        assertNull(policy.checkOrigin("https://evil.example.test"));
        assertNull(policy.checkOrigin("http://drive.example.test"));
        assertNull(policy.checkOrigin("https://drive.example.test:8443"));
        assertEquals(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"), policy.getAllowedMethods());
        assertEquals(List.of("Authorization", "Content-Type"), policy.getAllowedHeaders());
        assertFalse(policy.getAllowCredentials());
        assertNull(policy.getExposedHeaders());
    }
}
