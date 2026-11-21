package com.minidrive.controller;

import com.minidrive.dto.auth.AuthResponse;
import com.minidrive.dto.auth.LoginRequest;
import com.minidrive.dto.auth.RegisterRequest;
import com.minidrive.dto.auth.ChangePasswordRequest;
import com.minidrive.dto.user.UserResponse;
import com.minidrive.service.AuthService;
import com.minidrive.service.ClientIpResolver;
import com.minidrive.service.DistributedRateLimiter;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final DistributedRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public AuthController(
            AuthService authService,
            DistributedRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        rateLimiter.register(clientIpResolver.resolve(httpRequest), request.getEmail());

        AuthResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        rateLimiter.login(clientIpResolver.resolve(httpRequest), request.getEmail());

        return ResponseEntity.ok(
                authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        return ResponseEntity.ok(authService.getCurrentUser(authentication.getName()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(authentication.getName(), request);
        return ResponseEntity.noContent().build();
    }
}
