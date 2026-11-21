package com.minidrive.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails early when local authenticated Redis or MinIO credentials are absent. */
@Component
@Profile("dev")
public class DevelopmentRedisCredentialsValidator {
    public DevelopmentRedisCredentialsValidator(Environment environment) {
        List<String> missing = new ArrayList<>();
        if (environment.getProperty("spring.data.redis.username", "").isBlank()) missing.add("REDIS_USERNAME");
        if (environment.getProperty("spring.data.redis.password", "").isBlank()) missing.add("REDIS_PASSWORD");
        if (environment.getProperty("minio.access-key", "").isBlank()) missing.add("MINIO_ACCESS_KEY");
        if (environment.getProperty("minio.secret-key", "").isBlank()) missing.add("MINIO_SECRET_KEY");
        if (!missing.isEmpty()) throw new IllegalStateException("Invalid development configuration: "
                + String.join(", ", missing) + " are required for authenticated Redis and MinIO services");
    }
}
