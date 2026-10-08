package com.minidrive.dto.auth;

public class AuthResponse {

    private String token;
    private String tokenType;
    private String userId;
    private String name;
    private String email;

    public AuthResponse(
            String token,
            String userId,
            String name,
            String email) {
        this.token = token;
        this.tokenType = "Bearer";
        this.userId = userId;
        this.name = name;
        this.email = email;
    }

    public String getToken() {
        return token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }
}