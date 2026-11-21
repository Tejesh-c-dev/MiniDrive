package com.minidrive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private final Environment environment;
    private final String configuredOrigins;

    public CorsConfig(Environment environment,
                      @org.springframework.beans.factory.annotation.Value("${minidrive.cors.allowed-origins:}")
                      String configuredOrigins) {
        this.environment = environment;
        this.configuredOrigins = configuredOrigins;
    }

    /**
     * CORS policy applied globally via Spring Security's cors() integration.
     * Allows the Vite dev-server origin (http://localhost:5173) and the
     * production frontend origin (VITE_API_BASE_URL target) to call the API.
     *
     * <p>Only the HTTP methods actually used by the API are allowed;
     * Authorization and Content-Type are the only extra request headers needed.
     * Credentials (cookies) are not used — auth is stateless JWT via header.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            config.setAllowedOriginPatterns(List.of(
                    "http://localhost:[*]",
                    "http://127.0.0.1:[*]"
            ));
        } else {
            List<String> origins = Arrays.stream(configuredOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .toList();
            if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
                throw new IllegalArgumentException("CORS allowed origins must not contain wildcards");
            }
            config.setAllowedOrigins(origins);
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Authorization"));

        // Credentials are not sent (stateless JWT in Authorization header only).
        config.setAllowCredentials(false);

        // Cache preflight response for 30 minutes to reduce OPTIONS round-trips.
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
