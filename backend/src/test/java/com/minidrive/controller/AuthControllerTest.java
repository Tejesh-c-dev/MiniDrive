
package com.minidrive.controller;

import com.minidrive.entity.User;
import com.minidrive.dto.user.UserResponse;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;
import com.minidrive.service.DistributedRateLimiter;
import com.minidrive.service.RateLimitExceededException;
import com.minidrive.service.RateLimitUnavailableException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {
    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private DistributedRateLimiter rateLimiter;
    @Value("${jwt.secret}") private String jwtSecret;

    @BeforeEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void registrationPersistsIdentityAndReturnsJwtWithoutPasswordOrHash() throws Exception {
        String body = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Tejesh"))
                .andExpect(jsonPath("$.email").value("tejesh@test.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.*", not(containsString(PASSWORD))))
                .andReturn().getResponse().getContentAsString();

        User user = userRepository.findByEmail("tejesh@test.com").orElseThrow();
        String issuedToken = body.split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
        org.junit.jupiter.api.Assertions.assertEquals(user.getAuthVersion(), jwtService.extractAuthVersion(issuedToken));
        org.junit.jupiter.api.Assertions.assertNotNull(user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(PASSWORD, user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches(PASSWORD, user.getPasswordHash()));
    }

    @Test
    void duplicateAndCaseNormalizedEmailAreRejected() throws Exception {
        mockMvc.perform(register("Tejesh", "Tejesh@Test.com", PASSWORD))
                .andExpect(status().isCreated());
        mockMvc.perform(register("Other", "tejesh@test.com", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Email is already registered"));
        org.junit.jupiter.api.Assertions.assertEquals(1, userRepository.count());
        org.junit.jupiter.api.Assertions.assertTrue(userRepository.findByEmail("tejesh@test.com").isPresent());
    }

    @Test
    void loginWithCorrectCredentialsReturnsJwt() throws Exception {
        mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD)).andExpect(status().isCreated());
        mockMvc.perform(login("TEJESH@test.com", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("tejesh@test.com"));
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSameUnauthorizedResponseWithoutToken() throws Exception {
        mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD)).andExpect(status().isCreated());
        mockMvc.perform(login("tejesh@test.com", "incorrect-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mockMvc.perform(login("unknown@test.com", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void meRequiresTokenAndReturnsOnlyAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        registerAndGetToken("Tejesh", "tejesh@test.com");
        registerAndGetToken("Someone Else", "other@test.com");

        String token = loginAndGetToken("tejesh@test.com", PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tejesh"))
                .andExpect(jsonPath("$.email").value("tejesh@test.com"))
                .andExpect(jsonPath("$.id").value(userRepository.findByEmail("tejesh@test.com").orElseThrow().getId().toString()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void userResponseSerializesOnlyPublicIdentityFields() throws Exception {
        User user = userRepository.save(new User("Tejesh", "tejesh@test.com", "sensitive-hash"));
        String json = JsonMapper.builder().build().writeValueAsString(UserResponse.from(user));
        org.assertj.core.api.Assertions.assertThat(json).contains("id", "name", "email")
                .doesNotContain("password", "token", "createdAt", "updatedAt", "sensitive-hash");
    }

    @Test
    void meReturnsUnauthorizedForDeletedAuthenticatedUser() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        userRepository.delete(userRepository.findByEmail("tejesh@test.com").orElseThrow());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeValidatesCurrentAndNewPasswordAndRevokesOldToken() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        mockMvc.perform(changePassword(token, "wrong-password", "newpassword456"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(changePassword(token, PASSWORD, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
        mockMvc.perform(changePassword(token, PASSWORD, "newpassword456"))
                .andExpect(status().isNoContent());

        User changed = userRepository.findByEmail("tejesh@test.com").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1L, changed.getAuthVersion());
        org.junit.jupiter.api.Assertions.assertEquals(0L, jwtService.extractAuthVersion(token));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(login("tejesh@test.com", PASSWORD)).andExpect(status().isUnauthorized());
        String newToken = loginAndGetToken("tejesh@test.com", "newpassword456");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1L, jwtService.extractAuthVersion(newToken));
    }

    @Test
    void validLegacyJwtWithoutAuthVersionIsRejected() throws Exception {
        registerAndGetToken("Tejesh", "tejesh@test.com");
        String legacyToken = Jwts.builder().subject("tejesh@test.com")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIssuedByAnotherInstanceIsRevokedThroughSharedUserVersion() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        // A separate JwtService models another application node using the same
        // signing key. The validating node reads the shared database version.
        JwtService otherInstance = new JwtService(jwtSecret, 86_400_000);
        String otherInstanceToken = otherInstance.generateToken("tejesh@test.com", 0);

        mockMvc.perform(changePassword(token, PASSWORD, "newpassword456"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + otherInstanceToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeRevokesTokenEvenWhenUpdatedAtMatchesIssuanceMillisecond() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        long issuedAtMillis = jwtService.extractIssuedAt(token).getTime();
        mockMvc.perform(changePassword(token, PASSWORD, "newpassword456"))
                .andExpect(status().isNoContent());

        // Recreate the timestamp collision that allowed the old updated_at
        // comparison to preserve a token minted in the same millisecond.
        LocalDateTime issuanceTime = LocalDateTime.ofInstant(
                new Date(issuedAtMillis).toInstant(), ZoneId.systemDefault());
        jdbcTemplate.update("update users set updated_at = ? where email = ?", issuanceTime, "tejesh@test.com");
        User changed = userRepository.findByEmail("tejesh@test.com").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(issuedAtMillis,
                changed.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentLoginAndPasswordChangeCannotLeaveOldPasswordTokenValid() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var login = executor.submit(() -> mockMvc.perform(login("tejesh@test.com", PASSWORD))
                    .andReturn().getResponse());
            var change = executor.submit(() -> mockMvc.perform(changePassword(token, PASSWORD, "newpassword456"))
                    .andReturn().getResponse());
            var loginResponse = login.get();
            org.junit.jupiter.api.Assertions.assertTrue(loginResponse.getStatus() == 200
                    || loginResponse.getStatus() == 401);
            org.junit.jupiter.api.Assertions.assertEquals(204, change.get().getStatus());

            if (loginResponse.getStatus() == 200) {
                String issuedToken = loginResponse.getContentAsString()
                        .split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
                mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + issuedToken))
                        .andExpect(status().isUnauthorized());
            }
            org.junit.jupiter.api.Assertions.assertEquals(1L,
                    userRepository.findByEmail("tejesh@test.com").orElseThrow().getAuthVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulLoginUpgradesLegacyBcryptHash() throws Exception {
        User legacy = new User("Tejesh", "tejesh@test.com",
                new BCryptPasswordEncoder(4).encode(PASSWORD));
        userRepository.save(legacy);
        String oldHash = legacy.getPasswordHash();

        String token = mockMvc.perform(login("tejesh@test.com", PASSWORD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().split("\\\"token\\\":\\\"")[1].split("\\\"")[0];

        String upgradedHash = userRepository.findByEmail("tejesh@test.com").orElseThrow().getPasswordHash();
        org.assertj.core.api.Assertions.assertThat(upgradedHash).isNotEqualTo(oldHash);
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(PASSWORD, upgradedHash)).isTrue();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentPasswordChangesDoNotOverwriteCommittedChange() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> mockMvc.perform(changePassword(token, PASSWORD, "firstpassword456"))
                    .andReturn().getResponse().getStatus());
            var two = executor.submit(() -> mockMvc.perform(changePassword(token, PASSWORD, "secondpassword456"))
                    .andReturn().getResponse().getStatus());
            int oneStatus = one.get();
            int twoStatus = two.get();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(oneStatus, twoStatus))
                    .containsExactlyInAnyOrder(204, 401);
            String stored = userRepository.findByEmail("tejesh@test.com").orElseThrow().getPasswordHash();
            org.assertj.core.api.Assertions.assertThat(
                    passwordEncoder.matches("firstpassword456", stored)
                            || passwordEncoder.matches("secondpassword456", stored)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void meRejectsMalformedRandomAndInvalidSignatureTokens() throws Exception {
        String invalidSignature = Jwts.builder().subject("tejesh@test.com")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("different-test-signing-key-long-enough".getBytes(StandardCharsets.UTF_8)))
                .compact();
        for (String token : new String[] { "not-a-jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.random", invalidSignature }) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void meRejectsMalformedAuthorizationHeadersWithoutServerError() throws Exception {
        for (String header : new String[] { "Bearer", "Bearer ", "Bearer    ", "Basic malformed" }) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", header))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void meRejectsExpiredJwt() throws Exception {
        String expiredToken = new JwtService(jwtSecret, -1).generateToken("tejesh@test.com", 0);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsInvalidFieldsWithValidationResponse() throws Exception {
        String[][] invalidRequests = {
                {"", "person@test.com", PASSWORD},
                {"Name", "", PASSWORD},
                {"Name", "not-an-email", PASSWORD},
                {"Name", "person@test.com", ""},
                {"Name", "person@test.com", "short"}
        };
        for (String[] fields : invalidRequests) {
            mockMvc.perform(register(fields[0], fields[1], fields[2]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors").isMap());
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, userRepository.count());
    }

    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void loginLimitReturnsGeneric429AndRetryAfter() throws Exception {
        String email = "limited@test.com";
        org.mockito.Mockito.doThrow(new RateLimitExceededException(42))
                .when(rateLimiter).login(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(email));

        mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "42"))
                .andExpect(jsonPath("$.error").value("Too many requests. Please try again later."))
                .andExpect(jsonPath("$.error").value(not(containsString(email))));
    }

    @Test
    void registrationLimitReturnsGeneric429WithoutEmailDisclosure() throws Exception {
        String email = "existing-account@test.com";
        org.mockito.Mockito.doThrow(new RateLimitExceededException(60))
                .when(rateLimiter).register(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(email));

        mockMvc.perform(register("Name", email, PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "60"))
                .andExpect(jsonPath("$.error")
                        .value("Too many requests. Please try again later."))
                .andExpect(jsonPath("$.error").value(not(containsString(email))));
    }

    @Test
    void redisOutageReturnsServiceUnavailableInsteadOfBypassingProtection() throws Exception {
        org.mockito.Mockito.doThrow(new RateLimitUnavailableException(new IllegalStateException()))
                .when(rateLimiter).login(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq("redis-down@test.com"));

        mockMvc.perform(login("redis-down@test.com", PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(
                        "Request protection is temporarily unavailable"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String name, String email, String password) {
        return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changePassword(
            String token, String currentPassword, String newPassword) {
        return post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        return mockMvc.perform(register(name, email, PASSWORD)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        return mockMvc.perform(login(email, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
    }
}
