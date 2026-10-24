package com.minidrive.config;

import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "minidrive.cors.allowed-origins=https://drive.example.test,https://admin.example.test")
@AutoConfigureMockMvc
class CorsSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @Test
    void configuredOriginsReceivePreflightHeadersBeforeAuthentication() throws Exception {
        mockMvc.perform(options("/api/files")
                        .header("Origin", "https://drive.example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://drive.example.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("authorization")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mockMvc.perform(options("/api/files")
                        .header("Origin", "https://admin.example.test")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.test"));
    }

    @Test
    void disallowedOriginGetsNoCorsHeadersAndProtectedRouteStillRequiresJwt() throws Exception {
        mockMvc.perform(options("/api/files")
                        .header("Origin", "https://evil.example.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mockMvc.perform(get("/api/files").header("Origin", "https://drive.example.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://drive.example.test"));
    }

    @Test
    void bearerJwtWorksFromAllowedOriginAndHealthRemainsPublic() throws Exception {
        User user = userRepository.save(new User("CORS User", "cors-user@example.test", "unused"));
        mockMvc.perform(get("/api/auth/me")
                        .header("Origin", "https://drive.example.test")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user.getEmail())))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://drive.example.test"));

        mockMvc.perform(get("/api/health").header("Origin", "https://drive.example.test"))
                .andExpect(status().isOk());
    }
}
