package com.minidrive.controller;

import com.minidrive.dto.auth.AuthResponse;
import com.minidrive.dto.auth.LoginRequest;
import com.minidrive.dto.auth.RegisterRequest;
import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.service.AuthService;
import com.minidrive.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.minidrive.service.RefreshTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import com.minidrive.dto.auth.AuthResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;
    private final RefreshTokenService refreshTokens;
    private final boolean secureCookies;

    public AuthController(
            AuthService authService,
            UserRepository userRepository,
            RateLimitService rateLimitService,
            RefreshTokenService refreshTokens,
            @Value("${minidrive.auth.cookie-secure:false}") boolean secureCookies) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.rateLimitService = rateLimitService;
        this.refreshTokens = refreshTokens;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthResponse response = authService.register(request);
        String refresh = refreshTokens.create(userRepository.findByEmail(response.getEmail()).orElseThrow());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refresh).toString())
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {

        rateLimitService.loginAccount(
                (String) servletRequest.getAttribute("minidrive.rate-limit.ip"), request.getEmail());

        AuthResponse response = authService.login(request);
        String refresh = refreshTokens.create(userRepository.findByEmail(response.getEmail()).orElseThrow());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(refresh).toString()).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = "minidrive_refresh", required = false) String cookie) {
        try {
            RefreshTokenService.Rotation rotated = refreshTokens.rotate(cookie);
            AuthResponse response = new AuthResponse(authService.newAccessToken(rotated.user().getEmail()),
                    rotated.user().getId().toString(), rotated.user().getName(), rotated.user().getEmail());
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(rotated.token()).toString()).body(response);
        } catch (com.minidrive.service.RefreshTokenRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                    .body(Map.of("error", "Refresh token is invalid or expired"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @RequestHeader("Authorization") String authorizationHeader,
            @CookieValue(name = "minidrive_refresh", required = false) String refreshCookie) {
        authService.logout(authorizationHeader, authentication, refreshCookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            Authentication authentication) {

        User user = userRepository
                .findByEmail(authentication.getName())
                .orElseThrow();

        return ResponseEntity.ok(
                Map.of(
                        "id", user.getId(),
                        "name", user.getName(),
                        "email", user.getEmail()));
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from("minidrive_refresh", token).httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path("/api/auth").maxAge(java.time.Duration.ofDays(30)).build();
    }
    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from("minidrive_refresh", "").httpOnly(true).secure(secureCookies)
                .sameSite("Lax").path("/api/auth").maxAge(0).build();
    }
}
