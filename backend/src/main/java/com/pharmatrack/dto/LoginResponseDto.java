package com.pharmatrack.dto;

/**
 * Authentication response containing the signed JWT bearer token and safe user summary.
 */
public class LoginResponseDto {

    private String accessToken;
    private String tokenType;
    private long expiresInSeconds;
    private UserSummaryDto user;

    public LoginResponseDto() {
    }

    public LoginResponseDto(String accessToken, String tokenType, long expiresInSeconds, UserSummaryDto user) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresInSeconds = expiresInSeconds;
        this.user = user;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }

    public UserSummaryDto getUser() {
        return user;
    }

    public void setUser(UserSummaryDto user) {
        this.user = user;
    }
}

