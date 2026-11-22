package com.minidrive.service;

import com.minidrive.dto.auth.AuthResponse;
import com.minidrive.dto.auth.ChangePasswordRequest;
import com.minidrive.dto.auth.LoginRequest;
import com.minidrive.dto.auth.RegisterRequest;
import com.minidrive.dto.user.UserResponse;
import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService,
            TokenRevocationService tokenRevocationService, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.tokenRevocationService = tokenRevocationService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email is already registered");
        }
        User user = new User(request.getName().trim(), email, passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        return response(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            userRepository.saveAndFlush(user);
        }
        return response(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authentication is no longer valid"));
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new BadCredentialsException("Authentication is no longer valid"));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setAuthVersion(Math.addExact(user.getAuthVersion(), 1));
        userRepository.saveAndFlush(user);
        refreshTokenService.revokeAllForUser(user.getId());
    }

    @Transactional
    public void logout(String authorizationHeader, Authentication authentication, String refreshCookie) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Bearer token is required");
        }
        JwtService.ValidatedToken token = jwtService.parseValidatedToken(authorizationHeader.substring(7));
        if (!token.subject().equals(authentication.getName())) {
            throw new IllegalArgumentException("Bearer token does not match authenticated user");
        }
        tokenRevocationService.revoke(token);
        refreshTokenService.revoke(refreshCookie);
    }

    @Transactional(readOnly = true)
    public String newAccessToken(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authentication is no longer valid"));
        return jwtService.generateToken(user.getEmail(), user.getAuthVersion());
    }

    private AuthResponse response(User user) {
        return new AuthResponse(jwtService.generateToken(user.getEmail(), user.getAuthVersion()),
                user.getId().toString(), user.getName(), user.getEmail());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
