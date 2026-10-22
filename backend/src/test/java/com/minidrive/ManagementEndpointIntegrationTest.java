package com.minidrive;

import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the monitoring endpoints on the application listener. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.health.redis.enabled=false",
                "management.endpoint.health.validate-group-membership=false",
                "management.endpoint.health.show-components=always",
                "management.endpoint.health.group.database.include=db",
                "management.endpoints.web.exposure.include=health,prometheus"
        })
class ManagementEndpointIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @LocalServerPort private int apiPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void healthAndPrometheusAreAvailableOnApplicationListener() throws Exception {
        HttpResponse<String> health = get(apiPort, "/actuator/health", null);
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"UP\""));
        assertTrue(health.body().contains("\"components\""));
        assertTrue(health.body().contains("\"db\""));
        assertFalse(health.body().contains("\"details\""));

        HttpResponse<String> databaseHealth = get(apiPort, "/actuator/health/database", null);
        assertEquals(200, databaseHealth.statusCode());
        assertTrue(databaseHealth.body().contains("\"status\":\"UP\""));
        assertFalse(databaseHealth.body().contains("\"details\""));

        HttpResponse<String> prometheus = get(apiPort, "/actuator/prometheus", null);
        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.body().contains("jvm_memory_used_bytes"));
        assertTrue(prometheus.body().contains("process_uptime_seconds"));
        assertTrue(prometheus.body().contains("http_server_requests"));
        assertTrue(prometheus.body().contains("application=\"minidrive\""));

        String jwt = jwtForOrdinaryUser();
        HttpResponse<String> jwtPrometheus = get(apiPort, "/actuator/prometheus", jwt);
        assertEquals(200, jwtPrometheus.statusCode());
    }

    @Test
    void sensitiveManagementEndpointsAreDeniedEvenWithAnOrdinaryJwt() throws Exception {
        String jwt = jwtForOrdinaryUser();
        for (String endpoint : new String[] { "env", "configprops", "heapdump", "loggers", "mappings" }) {
            HttpResponse<String> response = get(apiPort, "/actuator/" + endpoint, jwt);
            assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                    () -> endpoint + " was unexpectedly accessible: " + response.statusCode());
        }

        HttpResponse<String> anonymousAttempt = get(apiPort, "/actuator/env", null);
        assertTrue(anonymousAttempt.statusCode() == 403 || anonymousAttempt.statusCode() == 404);
    }

    private String jwtForOrdinaryUser() {
        String email = "monitoring-user@test.com";
        userRepository.saveAndFlush(new User("Monitoring User", email, "not-used"));
        return jwtService.generateToken(email);
    }

    private HttpResponse<String> get(int port, String path, String jwt) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (jwt != null) {
            request.header("Authorization", "Bearer " + jwt);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
