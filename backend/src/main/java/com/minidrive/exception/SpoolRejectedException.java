package com.minidrive.exception;

public class SpoolRejectedException extends RuntimeException {
    public enum Reason { TOO_LARGE, CAPACITY, CONCURRENCY, UNAVAILABLE }
    private final Reason reason;

    public SpoolRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SpoolRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
