package com.minidrive.integration;

import com.minidrive.repository.RevokedTokenRepository;
import com.minidrive.security.JwtService;
import com.minidrive.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in durable revocation check against the isolated PostgreSQL test database. */
@SpringBootTest
@EnabledIfSystemProperty(named = "minidrive.live-token-revocation", matches = "true")
class TokenRevocationPostgresLiveTest {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("minidrive.live-token-revocation.jdbc-url"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getProperty("minidrive.live-token-revocation.db-user", "md_it"));
        registry.add("spring.datasource.password", () -> required("minidrive.live-token-revocation.db-password"));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private JwtService jwtService;
    @Autowired private TokenRevocationService revocations;
    @Autowired private RevokedTokenRepository records;

    @Test
    void revocationSurvivesAnIndependentServiceInstance() {
        JwtService.ValidatedToken token = jwtService.parseValidatedToken(jwtService.generateToken("pg-live-test@invalid"));
        revocations.revoke(token);

        TokenRevocationService independentInstance = new TokenRevocationService(records);
        assertTrue(independentInstance.isRevoked(token.tokenId()));
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing system property: " + key);
        return value;
    }
}
