package com.minidrive.service;

import com.minidrive.dto.auth.AuthResponse;
import com.minidrive.dto.auth.LoginRequest;
import com.minidrive.dto.auth.RegisterRequest;
import com.minidrive.dto.auth.ChangePasswordRequest;
import com.minidrive.dto.user.UserResponse;
import com.minidrive.entity.User;
import com.minidrive.repository.UserRepository;
import com.minidrive.security.JwtService;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException(
                    "Email is already registered");
        }

        User user = new User(
                request.getName().trim(),
                email,
                passwordEncoder.encode(request.getPassword()));

        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail(), user.getAuthVersion());

        return new AuthResponse(
                token,
                user.getId().toString(),
                user.getName(),
                user.getEmail());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {

        String email = normalizeEmail(request.getEmail());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        email,
                        request.getPassword()));

        // Serialize token issuance with password changes. If login wins the
        // row lock, the subsequent password change advances auth_version and
        // revokes its token; if password change wins, this reload sees the new
        // hash and the stale password is rejected below.
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Recheck against the latest persisted value in case a password change
        // committed after AuthenticationManager verified its loaded snapshot.
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            userRepository.saveAndFlush(user);
        }

        String token = jwtService.generateToken(user.getEmail(), user.getAuthVersion());

        return new AuthResponse(
                token,
                user.getId().toString(),
                user.getName(),
                user.getEmail());
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

        // Encoding the requested password also ensures it uses the current
        // BCrypt strength, including when the current stored hash is legacy.
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setAuthVersion(Math.addExact(user.getAuthVersion(), 1));
        userRepository.saveAndFlush(user);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
