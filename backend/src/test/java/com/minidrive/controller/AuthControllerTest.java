
package com.minidrive.controller;

import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.repository.RevokedTokenRepository;
import com.minidrive.repository.RefreshTokenRepository;
import com.minidrive.service.RefreshTokenService;
import com.minidrive.entity.RefreshToken;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.minidrive.security.RateLimitService;

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
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @MockitoBean private RateLimitService rateLimitService;
    @Value("${jwt.secret}") private String jwtSecret;

    @BeforeEach
    void cleanup() {
        revokedTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
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
    void passwordChangeAdvancesAuthVersionRevokesAccessAndRefreshTokens() throws Exception {
        var registered = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String accessToken = registered.getContentAsString().split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
        var refreshCookie = registered.getCookie("minidrive_refresh");
        org.junit.jupiter.api.Assertions.assertNotNull(refreshCookie);
        org.junit.jupiter.api.Assertions.assertEquals(1, refreshTokenRepository.count());

        mockMvc.perform(changePassword(accessToken, "wrong-password", "newpassword456"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(changePassword(accessToken, PASSWORD, "short"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(changePassword(accessToken, PASSWORD, "newpassword456"))
                .andExpect(status().isNoContent());

        User changed = userRepository.findByEmail("tejesh@test.com").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1L, changed.getAuthVersion());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
        String nextToken = loginAndGetToken("tejesh@test.com", "newpassword456");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + nextToken))
                .andExpect(status().isOk());
    }

    @Test
    void successfulLoginUpgradesLegacyBcryptHash() throws Exception {
        User legacy = new User("Tejesh", "tejesh@test.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(PASSWORD));
        userRepository.save(legacy);
        String oldHash = legacy.getPasswordHash();

        String token = loginAndGetToken("tejesh@test.com", PASSWORD);
        String upgradedHash = userRepository.findByEmail("tejesh@test.com").orElseThrow().getPasswordHash();
        org.assertj.core.api.Assertions.assertThat(upgradedHash).isNotEqualTo(oldHash);
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches(PASSWORD, upgradedHash)).isTrue();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentPasswordChangesCannotOverwriteTheCommittedChange() throws Exception {
        String token = registerAndGetToken("Tejesh", "tejesh@test.com");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> mockMvc.perform(
                    changePassword(token, PASSWORD, "firstpassword456")).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> mockMvc.perform(
                    changePassword(token, PASSWORD, "secondpassword456")).andReturn().getResponse().getStatus());
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(204, 401);
            String stored = userRepository.findByEmail("tejesh@test.com").orElseThrow().getPasswordHash();
            org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches("firstpassword456", stored)
                    || passwordEncoder.matches("secondpassword456", stored)).isTrue();
            org.junit.jupiter.api.Assertions.assertEquals(1L,
                    userRepository.findByEmail("tejesh@test.com").orElseThrow().getAuthVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refreshRotatesOpaqueCookieAndPersistsOnlyItsHash() throws Exception {
        var login = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String original = login.getCookie("minidrive_refresh").getValue();
        org.junit.jupiter.api.Assertions.assertTrue(login.getHeader("Set-Cookie").contains("Path=/api/auth"));
        org.junit.jupiter.api.Assertions.assertTrue(login.getHeader("Set-Cookie").contains("HttpOnly"));
        org.junit.jupiter.api.Assertions.assertTrue(login.getHeader("Set-Cookie").contains("SameSite=Lax"));
        org.junit.jupiter.api.Assertions.assertTrue(login.getHeader("Set-Cookie").contains("Max-Age=2592000"));
        org.junit.jupiter.api.Assertions.assertEquals(1, refreshTokenRepository.count());
        org.junit.jupiter.api.Assertions.assertTrue(refreshTokenRepository.existsById(RefreshTokenService.hash(original)));
        org.junit.jupiter.api.Assertions.assertFalse(refreshTokenRepository.existsById(original));

        var rotated = mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(login.getCookie("minidrive_refresh")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertNotEquals(original, rotated.getCookie("minidrive_refresh").getValue());
        org.junit.jupiter.api.Assertions.assertTrue(rotated.getHeader("Set-Cookie").contains("Path=/api/auth"));
        org.junit.jupiter.api.Assertions.assertTrue(rotated.getHeader("Set-Cookie").contains("HttpOnly"));
        org.junit.jupiter.api.Assertions.assertTrue(rotated.getHeader("Set-Cookie").contains("SameSite=Lax"));
        org.junit.jupiter.api.Assertions.assertTrue(rotated.getHeader("Set-Cookie").contains("Max-Age=2592000"));
        org.junit.jupiter.api.Assertions.assertEquals(2, refreshTokenRepository.count());
    }

    @org.junit.jupiter.api.Test
    void accessTokensExpireAfterFifteenMinutes() throws Exception {
        var expiration = jwtService.parseValidatedToken(jwtService.generateToken("user@test.com")).expiresAt();
        long remaining = java.time.Duration.between(java.time.Instant.now(), expiration).toSeconds();
        org.junit.jupiter.api.Assertions.assertTrue(remaining <= 900);
        org.junit.jupiter.api.Assertions.assertTrue(remaining > 890);
    }

    @Test
    void refreshCookieRequiresNonSimpleRequestHeaderForCsrfProtection() throws Exception {
        var login = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        mockMvc.perform(post("/api/auth/refresh").cookie(login.getCookie("minidrive_refresh")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(login.getCookie("minidrive_refresh")))
                .andExpect(status().isOk());
    }

    @Test
    void replayRevokesTheRefreshFamilyAndMalformedOrExpiredCookiesAreUnauthorized() throws Exception {
        var login = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var original = login.getCookie("minidrive_refresh");
        var rotated = mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(original)).andExpect(status().isOk()).andReturn().getResponse();
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(original)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(rotated.getCookie("minidrive_refresh")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Refresh token is invalid or expired"));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new jakarta.servlet.http.Cookie("minidrive_refresh", "malformed")))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(refreshTokenRepository.findAll().stream().allMatch(t -> t.getRevokedAt() != null));
    }

    @Test
    void expiredRefreshTokenIsRejectedAndLogoutRevokesItsSessionAndAccessJwt() throws Exception {
        var login = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String raw = login.getCookie("minidrive_refresh").getValue();
        String access = login.getContentAsString().split("\\\"token\\\":\\\"")[1].split("\\\"")[0];
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)
                        .cookie(login.getCookie("minidrive_refresh")))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Set-Cookie", containsString("Path=/api/auth")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Set-Cookie", containsString("SameSite=Lax")));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(login.getCookie("minidrive_refresh"))).andExpect(status().isUnauthorized());

        User user = userRepository.findByEmail("tejesh@test.com").orElseThrow();
        String expiredRaw = "A".repeat(43);
        refreshTokenRepository.saveAndFlush(new RefreshToken(RefreshTokenService.hash(expiredRaw),
                java.util.UUID.randomUUID(), user.getId(), java.time.Instant.now().minusSeconds(1)));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new jakarta.servlet.http.Cookie("minidrive_refresh", expiredRaw)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshesAllowOnlyOneRotationAndRejectTheReplayedFamily() throws Exception {
        var login = mockMvc.perform(register("Tejesh", "tejesh@test.com", PASSWORD))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var cookie = login.getCookie("minidrive_refresh");
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                gate.await();
                return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(cookie)).andReturn().getResponse();
            });
            var second = pool.submit(() -> {
                gate.await();
                return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(cookie)).andReturn().getResponse();
            });
            gate.countDown();
            int a = first.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatus();
            int b = second.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatus();
            org.junit.jupiter.api.Assertions.assertTrue((a == 200 && b == 401) || (a == 401 && b == 200));
        } finally {
            pool.shutdownNow();
        }
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changePassword(
            String token, String currentPassword, String newPassword) {
        return post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}");
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
