package org.eardream.devvault.user.dto;

import org.eardream.devvault.user.entity.User;

public record ProfileResponse(
        String username,
        String email,
        boolean hasProfileImage
) {
    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getUsername(), user.getEmail(),
                user.getUserImage() != null && !user.getUserImage().isBlank());
    }
}
