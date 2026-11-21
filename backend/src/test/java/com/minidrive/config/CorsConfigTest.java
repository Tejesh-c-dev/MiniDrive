package com.minidrive.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    @Test
    void developmentAndTestAllowLocalhostOriginsOnArbitraryPorts() {
        for (String profile : new String[]{"dev", "test"}) {
            MockEnvironment environment = environment(profile);
            CorsConfiguration cors = configuration(environment, "");

            assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
            assertThat(cors.checkOrigin("http://127.0.0.1:4321")).isEqualTo("http://127.0.0.1:4321");
        }
    }

    @Test
    void productionAllowsOnlyConfiguredExactOrigins() {
        MockEnvironment environment = environment("prod");
        CorsConfiguration cors = configuration(environment, "https://app.example.com, https://admin.example.com");

        assertThat(cors.checkOrigin("https://app.example.com")).isEqualTo("https://app.example.com");
        assertThat(cors.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cors.checkOrigin("https://other.example.com")).isNull();
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
    }

    @Test
    void productionWithoutConfiguredOriginsDeniesAllOrigins() {
        MockEnvironment environment = environment("prod");
        CorsConfiguration cors = configuration(environment, "");

        assertThat(cors.checkOrigin("http://localhost:5173")).isNull();
        assertThat(cors.checkOrigin("https://app.example.com")).isNull();
    }

    @Test
    void productionRejectsWildcardConfiguration() {
        MockEnvironment environment = environment("prod");
        CorsConfig config = new CorsConfig(environment, "*");

        assertThatThrownBy(config::corsConfigurationSource)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain wildcards");
    }

    private CorsConfiguration configuration(MockEnvironment environment, String origins) {
        CorsConfigurationSource source = new CorsConfig(environment, origins).corsConfigurationSource();
        return source.getCorsConfiguration(new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/files"));
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
