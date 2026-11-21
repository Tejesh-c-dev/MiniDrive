package com.minidrive.service;

public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) {
        super("Rate limiting is temporarily unavailable", cause);
    }
}
