package com.minidrive.dto.sharelink;

/**
 * Minimal, safe metadata about a file reachable through a valid public share
 * link. Deliberately excludes the file id, owner, checksum, storage object key
 * and any internal path so an anonymous visitor learns nothing private.
 */
public class SharedFileResponse {

    private final String name;
    private final String contentType;
    private final long sizeBytes;

    public SharedFileResponse(String name, String contentType, long sizeBytes) {
        this.name = name;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public String getName() {
        return name;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
