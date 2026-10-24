package com.minidrive.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** Rejects unsafe or incomplete production configuration without echoing values. */
@Component
@Profile("prod")
public class ProductionConfigurationValidator {
    private static final List<String> KNOWN_NON_PRODUCTION_JWT_SECRETS = List.of(
            "change-this-development-secret-key-to-at-least-32-characters",
            "test-secret-key-that-is-long-enough-for-hmac-sha");

    public ProductionConfigurationValidator(Environment environment) {
        List<String> invalid = new ArrayList<>();
        require(environment, "spring.datasource.url", "DB_URL", invalid);
        require(environment, "spring.datasource.username", "DB_USERNAME", invalid);
        require(environment, "spring.datasource.password", "DB_PASSWORD", invalid);
        require(environment, "spring.data.redis.host", "REDIS_HOST", invalid);
        require(environment, "spring.data.redis.username", "REDIS_USERNAME", invalid);
        require(environment, "spring.data.redis.password", "REDIS_PASSWORD", invalid);
        require(environment, "jwt.secret", "JWT_SECRET", invalid);
        require(environment, "minio.endpoint", "MINIO_ENDPOINT", invalid);
        require(environment, "minio.access-key", "MINIO_ACCESS_KEY", invalid);
        require(environment, "minio.secret-key", "MINIO_SECRET_KEY", invalid);
        require(environment, "minio.bucket", "MINIO_BUCKET", invalid);
        require(environment, "minidrive.cors.allowed-origins", "MINIDRIVE_CORS_ALLOWED_ORIGINS", invalid);

        String url = environment.getProperty("spring.datasource.url", "").trim();
        if (!url.startsWith("jdbc:postgresql:")) invalid.add("DB_URL must be a PostgreSQL JDBC URL");
        positivePort(environment.getProperty("spring.data.redis.port"), "REDIS_PORT", invalid);
        rejectValue(environment, "spring.data.redis.password", "change-me-redis-password", "REDIS_PASSWORD", invalid);

        String jwt = environment.getProperty("jwt.secret", "");
        if (!jwt.isBlank() && (jwt.getBytes(StandardCharsets.UTF_8).length < 32
                || KNOWN_NON_PRODUCTION_JWT_SECRETS.contains(jwt))) {
            invalid.add("JWT_SECRET must be at least 32 UTF-8 bytes and must not use a known development or test value");
        }
        rejectValue(environment, "spring.datasource.username", "minidrive", "DB_USERNAME", invalid);
        rejectValue(environment, "spring.datasource.password", "minidrive", "DB_PASSWORD", invalid);
        rejectValue(environment, "spring.datasource.password", "change-me", "DB_PASSWORD", invalid);
        rejectValue(environment, "minio.access-key", "minioadmin", "MINIO_ACCESS_KEY", invalid);
        rejectValue(environment, "minio.secret-key", "minioadmin", "MINIO_SECRET_KEY", invalid);
        rejectValue(environment, "minio.secret-key", "change-me-minio-password", "MINIO_SECRET_KEY", invalid);

        String origins = environment.getProperty("minidrive.cors.allowed-origins", "");
        if (!origins.isBlank()) {
            try {
                for (String origin : CorsConfig.parseOrigins(origins)) {
                    if (!origin.startsWith("https://") || origin.contains("localhost") || origin.contains("127.0.0.1")) {
                        invalid.add("MINIDRIVE_CORS_ALLOWED_ORIGINS must contain only HTTPS production origins");
                        break;
                    }
                }
            } catch (IllegalArgumentException exception) {
                invalid.add("MINIDRIVE_CORS_ALLOWED_ORIGINS must be a comma-separated list of exact HTTP origins");
            }
        }

        if (!environment.getProperty("minidrive.rate-limit.trust-proxy", Boolean.class, false)) {
            invalid.add("MINIDRIVE_RATE_LIMIT_TRUST_PROXY must be true behind the HTTPS ingress");
        }
        String trusted = environment.getProperty("minidrive.rate-limit.trusted-proxies", "");
        if (trusted.isBlank()) {
            invalid.add("MINIDRIVE_RATE_LIMIT_TRUSTED_PROXIES must list trusted proxy IPs or CIDRs");
        } else {
            try {
                for (String cidr : trusted.split(",", -1)) {
                    if (cidr.isBlank()) throw new IllegalArgumentException();
                    new IpAddressMatcher(cidr.trim());
                }
            } catch (IllegalArgumentException exception) {
                invalid.add("MINIDRIVE_RATE_LIMIT_TRUSTED_PROXIES must contain valid IP addresses or CIDRs");
            }
        }

        boolean secondary = environment.getProperty("minidrive.storage.nodes[1].enabled", Boolean.class, false);
        if (secondary) {
            require(environment, "minidrive.storage.nodes[1].endpoint", "MINIO_SECONDARY_ENDPOINT", invalid);
            require(environment, "minidrive.storage.nodes[1].access-key", "MINIO_SECONDARY_ACCESS_KEY", invalid);
            require(environment, "minidrive.storage.nodes[1].secret-key", "MINIO_SECONDARY_SECRET_KEY", invalid);
            require(environment, "minidrive.storage.nodes[1].bucket", "MINIO_SECONDARY_BUCKET", invalid);
        }

        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Invalid production configuration: " + String.join("; ", invalid));
        }
    }

    private static void require(Environment env, String property, String key, List<String> errors) {
        if (env.getProperty(property, "").isBlank()) errors.add(key + " is required and must not be blank");
    }

    private static void positivePort(String value, String key, List<String> errors) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            if (port < 1 || port > 65535) errors.add(key + " must be between 1 and 65535");
        } catch (NumberFormatException exception) {
            errors.add(key + " must be between 1 and 65535");
        }
    }

    private static void rejectValue(Environment env, String property, String unsafe, String key, List<String> errors) {
        if (unsafe.equals(env.getProperty(property))) errors.add(key + " must not use a known development value");
    }
}
