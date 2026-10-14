package com.minidrive.util;

import java.util.UUID;

/**
 * The single source of truth for content object-key naming.
 *
 * <p>Every stored content object lives under its owner's namespace prefixed with
 * a random UUID, followed by a sanitized form of the original filename. The
 * random component guarantees a fresh key is never reused, so a replacement can
 * never overwrite an existing version's bytes. Keeping this in one place means
 * the classic upload, the replacement flow and the chunked-upload finalization
 * all write to the same convention.</p>
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /** A fresh, never-reused storage key for one stored content object. */
    public static String contentObjectKey(UUID ownerId, String originalFilename) {
        return ownerId + "/" + UUID.randomUUID() + "-" + sanitizeFilename(originalFilename);
    }

    /** Reduces a filename to a storage-safe suffix, never empty. */
    private static String sanitizeFilename(String name) {
        String safe = name == null ? "" : name;
        safe = safe.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        return safe.isBlank() ? "file" : safe;
    }
}
