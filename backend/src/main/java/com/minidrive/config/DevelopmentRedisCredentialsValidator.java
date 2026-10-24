package com.minidrive.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails early when the local authenticated Redis service lacks app credentials. */
@Component
@Profile("dev")
public class DevelopmentRedisCredentialsValidator {
    public DevelopmentRedisCredentialsValidator(Environment environment) {
        if (environment.getProperty("spring.data.redis.username", "").isBlank()
                || environment.getProperty("spring.data.redis.password", "").isBlank()) {
            throw new IllegalStateException(
                    "Invalid development configuration: REDIS_USERNAME and REDIS_PASSWORD are required for authenticated Redis");
        }
    }
}
