package com.minidrive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${minidrive.cors.allowed-origins}") String configuredOrigins) {
        this.allowedOrigins = parseOrigins(configuredOrigins);
    }

    static List<String> parseOrigins(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            throw new IllegalArgumentException("minidrive.cors.allowed-origins must contain at least one origin");
        }
        List<String> origins = Arrays.stream(configuredOrigins.split(",", -1))
                .map(String::trim)
                .toList();
        if (origins.isEmpty() || origins.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("minidrive.cors.allowed-origins contains an empty origin");
        }
        for (String origin : origins) {
            validateOrigin(origin);
        }
        return origins;
    }

    private static void validateOrigin(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getPort() < -1
                    || uri.getPort() > 65535
                    || uri.getRawAuthority() == null
                    || uri.getRawAuthority().endsWith(":")
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || value.contains("*")
                    || !uri.toString().equals(value)) {
                throw new IllegalArgumentException("invalid origin '" + value
                        + "'; expected an exact http(s)://hostname[:port] origin without path");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid CORS origin '" + value
                    + "'; expected an exact http(s)://hostname[:port] origin without path", exception);
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
