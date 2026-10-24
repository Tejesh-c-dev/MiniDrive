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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;

    public AuthController(
            AuthService authService,
            UserRepository userRepository,
            RateLimitService rateLimitService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {

        rateLimitService.loginAccount(
                (String) servletRequest.getAttribute("minidrive.rate-limit.ip"), request.getEmail());

        return ResponseEntity.ok(
                authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @RequestHeader("Authorization") String authorizationHeader) {
        authService.logout(authorizationHeader, authentication);
        return ResponseEntity.noContent().build();
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
}
