package com.minidrive.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;

class ProductionConfigurationValidatorTest {
    private static final String JWT = "production-signing-secret-with-at-least-32-bytes";

    @Test
    void localConfigurationDefaultsToDevAndImportsRootEnvOnlyThere() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(loader
                .load("application", new FileSystemResource("src/main/resources/application.yml")).get(0));
        assertTrue("dev".equals(environment.getProperty("spring.profiles.default")));
        assertTrue("localhost".equals(resolve(environment, "spring.data.redis.host")));
        assertTrue("56379".equals(resolve(environment, "spring.data.redis.port")));

        environment.getPropertySources().addFirst(loader
                .load("development", new ClassPathResource("application-dev.yml")).get(0));
        assertTrue(resolve(environment, "spring.config.import").contains("../.env"));

        PropertySource<?> production = loader
                .load("production", new ClassPathResource("application-prod.yml")).get(0);
        assertTrue(production.getProperty("spring.config.import") == null);
    }

    @Test
    void acceptsAValidProductionConfiguration() {
        assertDoesNotThrow(() -> new ProductionConfigurationValidator(validEnvironment()));
    }

    @Test
    void rejectsMissingProductionRedisCredentialsWithoutEchoingValues() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.data.redis.username", " ");
        environment.setProperty("spring.data.redis.password", "");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment));
        assertTrue(error.getMessage().contains("REDIS_USERNAME"));
        assertTrue(error.getMessage().contains("REDIS_PASSWORD"));
        assertTrue(!error.getMessage().contains("production-redis-password"));
    }

    @Test
    void productionYamlOverridesDevelopmentCredentialDefaults() throws Exception {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("MINIO_ACCESS_KEY", "minidrive-service");
        environment.setProperty("MINIO_SECRET_KEY", "production-storage-secret");
        environment.setProperty("DB_USERNAME", "minidrive_service");
        environment.setProperty("REDIS_HOST", "redis.internal");
        PropertySource<?> production = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml")).get(0);
        environment.getPropertySources().addFirst(production);

        assertTrue(resolve(environment, "minio.access-key").equals("minidrive-service"));
        assertTrue(resolve(environment, "minio.secret-key").equals("production-storage-secret"));
        assertTrue(resolve(environment, "spring.datasource.username").equals("minidrive_service"));
        assertTrue(resolve(environment, "spring.data.redis.host").equals("redis.internal"));
    }

    @Test
    void productionYamlDoesNotFallBackToDevelopmentStorageCredentials() throws Exception {
        MockEnvironment environment = validEnvironment();
        environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml")).get(0));
        environment.setProperty("MINIO_ACCESS_KEY", "");
        environment.setProperty("MINIO_SECRET_KEY", "");

        assertTrue(resolve(environment, "minio.access-key").isBlank());
        assertTrue(resolve(environment, "minio.secret-key").isBlank());
    }

    @Test
    void rejectsMissingJwtAndDoesNotEchoSecretValues() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("jwt.secret", "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment));
        assertTrue(error.getMessage().contains("JWT_SECRET"));
        assertTrue(!error.getMessage().contains(JWT));
    }

    @Test
    void rejectsShortOrKnownNonProductionJwtValues() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("jwt.secret", "too-short");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage().contains("JWT_SECRET"));

        environment.setProperty("jwt.secret", "change-this-development-secret-key-to-at-least-32-characters");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage().contains("JWT_SECRET"));

        environment.setProperty("jwt.secret", "test-secret-key-that-is-long-enough-for-hmac-sha");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage().contains("JWT_SECRET"));
    }

    @Test
    void rejectsMissingDatabaseAndStorageCredentials() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.username", "");
        environment.setProperty("spring.datasource.password", "");
        environment.setProperty("minio.access-key", "");
        environment.setProperty("minio.secret-key", "");

        String message = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage();
        assertTrue(message.contains("DB_USERNAME"));
        assertTrue(message.contains("DB_PASSWORD"));
        assertTrue(message.contains("MINIO_ACCESS_KEY"));
        assertTrue(message.contains("MINIO_SECRET_KEY"));
    }

    @Test
    void rejectsKnownDevelopmentDatabaseAndStorageCredentials() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.username", "minidrive");
        environment.setProperty("minio.access-key", "minioadmin");

        String message = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage();
        assertTrue(message.contains("DB_USERNAME"));
        assertTrue(message.contains("MINIO_ACCESS_KEY"));
    }

    @Test
    void rejectsMissingRedisAndInvalidCors() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.data.redis.host", "");
        environment.setProperty("spring.data.redis.port", "0");
        environment.setProperty("minidrive.cors.allowed-origins", "*");

        String message = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage();
        assertTrue(message.contains("REDIS_HOST"));
        assertTrue(message.contains("REDIS_PORT"));
        assertTrue(message.contains("MINIDRIVE_CORS_ALLOWED_ORIGINS"));
    }

    @Test
    void requiresTrustedProxyConfigurationAndSecureCorsOrigins() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("minidrive.rate-limit.trusted-proxies", "");
        environment.setProperty("minidrive.cors.allowed-origins", "http://drive.example.invalid");

        String message = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage();
        assertTrue(message.contains("MINIDRIVE_RATE_LIMIT_TRUSTED_PROXIES"));
        assertTrue(message.contains("MINIDRIVE_CORS_ALLOWED_ORIGINS"));
    }

    @Test
    void requiresSecondaryStorageCredentialsOnlyWhenEnabled() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("minidrive.storage.nodes[1].enabled", "true");

        String message = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment)).getMessage();
        assertTrue(message.contains("MINIO_SECONDARY_ACCESS_KEY"));
        assertTrue(message.contains("MINIO_SECONDARY_SECRET_KEY"));
    }

    private static MockEnvironment validEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put("spring.datasource.url", "jdbc:postgresql://db.internal:5432/minidrive");
        values.put("spring.datasource.username", "minidrive_service");
        values.put("spring.datasource.password", "production-database-password");
        values.put("spring.data.redis.host", "redis.internal");
        values.put("spring.data.redis.port", "6379");
        values.put("spring.data.redis.username", "minidrive_app");
        values.put("spring.data.redis.password", "production-redis-password");
        values.put("jwt.secret", JWT);
        values.put("minio.endpoint", "http://silo.internal:9000");
        values.put("minio.access-key", "minidrive-service");
        values.put("minio.secret-key", "production-storage-secret");
        values.put("minio.bucket", "private-files");
        values.put("minidrive.cors.allowed-origins", "https://drive.example.invalid");
        values.put("minidrive.rate-limit.trust-proxy", "true");
        values.put("minidrive.rate-limit.trusted-proxies", "10.2.0.0/16");
        values.put("minidrive.storage.nodes[1].enabled", "false");
        MockEnvironment environment = new MockEnvironment();
        values.forEach(environment::setProperty);
        return environment;
    }

    private static String resolve(MockEnvironment environment, String key) {
        return environment.resolvePlaceholders(environment.getProperty(key));
    }
}
