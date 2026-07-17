package org.eardream.devvault.auth;

public record AuthToken(String accessToken, String tokenType, long expiresIn) {
}
