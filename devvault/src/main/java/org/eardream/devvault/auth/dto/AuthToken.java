package org.eardream.devvault.auth.dto;

public record AuthToken(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
