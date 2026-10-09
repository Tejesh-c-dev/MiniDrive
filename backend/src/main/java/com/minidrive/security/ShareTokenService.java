package com.minidrive.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes public share-link bearer tokens.
 *
 * <p>Tokens are 256 bits from a cryptographically secure generator and are
 * encoded URL-safe so they can travel in a path segment. Only the SHA-256 hash
 * of a token is ever persisted: the raw token is returned to the owner once and
 * never logged.</p>
 */
@Component
public class ShareTokenService {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** Generates a fresh, high-entropy, URL-safe bearer token. */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Returns the lowercase hex SHA-256 hash of a raw token. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present on every supported JVM.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
