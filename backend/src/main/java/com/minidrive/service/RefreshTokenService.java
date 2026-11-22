package com.minidrive.service;

import com.minidrive.entity.RefreshToken;
import com.minidrive.entity.User;
import com.minidrive.repository.RefreshTokenRepository;
import com.minidrive.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class RefreshTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_DAYS = 30;
    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final long lifetimeDays;
    private final TransactionTemplate transactions;

    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, PlatformTransactionManager transactionManager,
            @Value("${minidrive.auth.refresh-lifetime-days:30}") long lifetimeDays) {
        this.tokens = tokens;
        this.users = users;
        this.lifetimeDays = lifetimeDays > 0 ? lifetimeDays : LIFETIME_DAYS;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public String create(User user) {
        String raw = generate();
        transactions.executeWithoutResult(status -> tokens.save(new RefreshToken(hash(raw), UUID.randomUUID(), user.getId(), Instant.now().plus(lifetimeDays, ChronoUnit.DAYS))));
        return raw;
    }

    /** Rotation work commits before a replay failure is reported, preserving family revocation. */
    public Rotation rotate(String raw) {
        if (!wellFormed(raw)) throw new RefreshTokenRejectedException();
        Rotation result = transactions.execute(status -> rotateLocked(raw));
        if (result == null) throw new RefreshTokenRejectedException();
        return result;
    }

    private Rotation rotateLocked(String raw) {
        String oldHash = hash(raw);
        RefreshToken current = tokens.findByHashForUpdate(oldHash).orElse(null);
        if (current == null) return null;
        if (current.getRevokedAt() != null) {
            tokens.revokeFamily(current.getFamilyId(), Instant.now());
            return null;
        }
        if (!current.getExpiresAt().isAfter(Instant.now())) {
            current.revoke(Instant.now(), null);
            return null;
        }
        User user = users.findById(current.getUserId()).orElse(null);
        if (user == null) return null;
        String replacement = generate();
        String replacementHash = hash(replacement);
        current.revoke(Instant.now(), replacementHash);
        tokens.saveAndFlush(new RefreshToken(replacementHash, current.getFamilyId(), user.getId(), current.getExpiresAt()));
        return new Rotation(replacement, user);
    }

    public void revoke(String raw) {
        if (!wellFormed(raw)) return;
        transactions.executeWithoutResult(status -> revokeLocked(raw));
    }

    private void revokeLocked(String raw) {
        RefreshToken token = tokens.findByHashForUpdate(hash(raw)).orElse(null);
        if (token != null) {
            tokens.revokeFamily(token.getFamilyId(), Instant.now());
        }
    }

    public void revokeAllForUser(UUID userId) {
        transactions.executeWithoutResult(status -> tokens.revokeAllForUser(userId, Instant.now()));
    }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean wellFormed(String raw) { return raw != null && raw.matches("[A-Za-z0-9_-]{43}"); }
    public record Rotation(String token, User user) {}
}
