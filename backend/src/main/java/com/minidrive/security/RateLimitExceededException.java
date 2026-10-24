package com.minidrive.security;
public class RateLimitExceededException extends RuntimeException {
    private final int retryAfter;
    public RateLimitExceededException(int retryAfter) { super("Too many requests"); this.retryAfter=retryAfter; }
    public int getRetryAfter() { return retryAfter; }
}
