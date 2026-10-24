package com.minidrive.security;
public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) { super(cause); }
}
