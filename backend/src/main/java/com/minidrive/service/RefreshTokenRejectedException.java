package com.minidrive.service;

public class RefreshTokenRejectedException extends RuntimeException {
    public RefreshTokenRejectedException() { super("Refresh token is invalid or expired"); }
}
