package org.eardream.devvault.auth;

public record AuthToken(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
