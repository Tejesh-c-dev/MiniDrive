package com.minidrive;

import com.minidrive.entity.File;
import com.minidrive.entity.User;
import com.minidrive.repository.FileRepository;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;
import com.minidrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Regresses the Tomcat/Spring Security race on real HTTP streaming responses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false",
        "management.endpoints.web.exposure.include=health,prometheus",
        "logging.level.com.minidrive.logging.CorrelationIdFilter=OFF"
})
class StreamingDownloadHeaderConcurrencyIntegrationTest {

    private static final byte[] CONTENT = "streaming download regression".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private StorageService storageService;

    @Test
    void repeatedHttp11StreamsKeepHeadersValidAndSecurityHeadersPresent() throws Exception {
        String email = "stream-regression-" + UUID.randomUUID() + "@test.com";
        User owner = userRepository.saveAndFlush(new User("Stream Regression", email, "unused"));
        String objectKey = UUID.randomUUID().toString();
        File file = new File(owner, null, "repeated.txt", "text/plain", CONTENT.length, null);
        file.setObjectKey(objectKey);
        file = fileRepository.saveAndFlush(file);
        when(storageService.download(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(CONTENT));

        URI uri = URI.create("http://127.0.0.1:" + port + "/api/files/" + file.getId() + "/download");
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + jwtService.generateToken(email))
                .GET()
                .build();

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> transfers = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                transfers.add(workers.submit(() -> {
                    for (int requestNumber = 0; requestNumber < 1_000; requestNumber++) {
                        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(200, response.statusCode(), "download response status");
                        assertArrayEquals(CONTENT, response.body(), "download response bytes");
                        assertEquals("attachment; filename=\"repeated.txt\"",
                                response.headers().firstValue("Content-Disposition").orElseThrow());
                        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
                    }
                    return null;
                }));
            }
            for (Future<Void> transfer : transfers) {
                transfer.get();
            }
        } finally {
            workers.shutdownNow();
            fileRepository.delete(file);
            userRepository.delete(owner);
        }
    }
}
