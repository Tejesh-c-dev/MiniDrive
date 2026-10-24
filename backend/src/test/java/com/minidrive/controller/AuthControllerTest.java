
package com.minidrive.controller;

import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.repository.RevokedTokenRepository;
import com.minidrive.entity.RevokedToken;
import com.minidrive.security.JwtService;
import com.minidrive.service.TokenRevocationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

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
    @Autowired private RevokedTokenRepository revokedTokenRepository;
    @Autowired private TokenRevocationService tokenRevocationService;
    @Value("${jwt.secret}") private String jwtSecret;

    @BeforeEach
    void cleanup() {
        revokedTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void newlyIssuedTokensHaveDistinctIdentifiers() {
        var first = jwtService.parseValidatedToken(jwtService.generateToken("user@test.com"));
        var second = jwtService.parseValidatedToken(jwtService.generateToken("user@test.com"));
        org.junit.jupiter.api.Assertions.assertNotNull(first.tokenId());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.tokenId(), second.tokenId());
    }

    @Test
    void registrationPersistsIdentityAndReturnsJwtWithoutPasswordOrHash() throws Exception {
        mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Tejesh"))
                .andExpect(jsonPath("$.email").value("tejesh@test.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.*", not(containsString(PASSWORD))));

        User user = userRepository.findByEmail("tejesh@test.com").orElseThrow();
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
    void meRejectsMalformedRandomAndInvalidSignatureTokens() throws Exception {
        String invalidSignature = Jwts.builder().subject("tejesh@test.com")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("different-test-signing-key-long-enough".getBytes(StandardCharsets.UTF_8)))
                .compact();
        String missingIdentifier = Jwts.builder().subject("tejesh@test.com")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        for (String token : new String[] { "not-a-jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.random", invalidSignature, missingIdentifier }) {
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
        String expiredToken = new JwtService(jwtSecret, -1).generateToken("tejesh@test.com");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyPresentedTokenAndPersistsAcrossServiceInstances() throws Exception {
        registerAndGetToken("Tejesh", "tejesh@test.com");
        String first = loginAndGetToken("tejesh@test.com", PASSWORD);
        String second = loginAndGetToken("tejesh@test.com", PASSWORD);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent());

        // A new service instance queries the same database-backed registry.
        TokenRevocationService restarted = new TokenRevocationService(revokedTokenRepository);
        String firstId = jwtService.parseValidatedToken(first).tokenId();
        org.junit.jupiter.api.Assertions.assertTrue(restarted.isRevoked(firstId));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, revokedTokenRepository.count());
    }

    @Test
    void repeatedLogoutIsRejectedAsUnauthenticatedAfterFirstSuccessfulRevocation() throws Exception {
        registerAndGetToken("Tejesh", "tejesh@test.com");
        String token = loginAndGetToken("tejesh@test.com", PASSWORD);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(1, revokedTokenRepository.count());
    }

    @Test
    void cleanupRetainsRecordsUntilTokenExpirationAndDeletesOnlyExpiredRows() {
        revokedTokenRepository.save(new RevokedToken("expired-jti", java.time.Instant.now().minusSeconds(1)));
        revokedTokenRepository.save(new RevokedToken("active-jti", java.time.Instant.now().plusSeconds(3600)));

        org.junit.jupiter.api.Assertions.assertEquals(1, tokenRevocationService.cleanupExpiredBatch());
        org.junit.jupiter.api.Assertions.assertFalse(revokedTokenRepository.existsById("expired-jti"));
        org.junit.jupiter.api.Assertions.assertTrue(revokedTokenRepository.existsById("active-jti"));
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String name, String email, String password) {
        return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(request -> { request.setRemoteAddr(testClientIp()); return request; });
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .with(request -> { request.setRemoteAddr(testClientIp()); return request; });
    }

    private static String testClientIp() {
        return "198.18." + ThreadLocalRandom.current().nextInt(256) + "."
                + ThreadLocalRandom.current().nextInt(1, 255);
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
