package org.eardream.devvault.auth;

import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;

    @Test
    void issueStoresHashInsteadOfRawToken() {
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenRepository, 60_000L);
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();

        String rawToken = refreshTokenService.issue(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertNotEquals(rawToken, captor.getValue().getTokenHash());
        assertEquals(64, captor.getValue().getTokenHash().length());
    }

    @Test
    void rotateRevokesOldTokenAndIssuesNewOne() {
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenRepository, 60_000L);
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();
        RefreshToken stored = RefreshToken.builder()
                .user(user)
                .tokenHash(RefreshTokenService.hash("old-token"))
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("old-token")))
                .thenReturn(Optional.of(stored));

        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate("old-token");

        verify(refreshTokenRepository).delete(stored);
        verify(refreshTokenRepository).save(any());
        assertEquals(user, rotated.user());
        assertNotEquals("old-token", rotated.refreshToken());
    }

    @Test
    void rotateRejectsExpiredToken() {
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenRepository, 60_000L);
        RefreshToken stored = RefreshToken.builder()
                .user(User.builder().email("user@example.com").password("encoded").username("user").build())
                .tokenHash(RefreshTokenService.hash("old-token"))
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("old-token")))
                .thenReturn(Optional.of(stored));

        assertThrows(ResponseStatusException.class, () -> refreshTokenService.rotate("old-token"));
    }
}
