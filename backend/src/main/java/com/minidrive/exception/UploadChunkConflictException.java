package com.minidrive.exception;

/** A chunk index has already been accepted with a different payload. */
public class UploadChunkConflictException extends RuntimeException {
    public UploadChunkConflictException(String message) {
        super(message);
    }
}
