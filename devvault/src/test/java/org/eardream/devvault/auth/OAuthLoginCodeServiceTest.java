package org.eardream.devvault.auth;

import org.eardream.devvault.auth.entity.OAuthLoginCode;
import org.eardream.devvault.auth.repository.OAuthLoginCodeRepository;
import org.eardream.devvault.auth.service.OAuthLoginCodeService;
import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginCodeServiceTest {

    @Mock
    OAuthLoginCodeRepository repository;

    @Test
    void issueStoresOnlyHashedOneTimeCode() {
        OAuthLoginCodeService service = new OAuthLoginCodeService(repository, 60_000L);
        User user = User.builder()
                .email("user@example.com")
                .password("encoded")
                .username("User")
                .build();

        String rawCode = service.issue(user);

        ArgumentCaptor<OAuthLoginCode> captor = ArgumentCaptor.forClass(OAuthLoginCode.class);
        verify(repository).save(captor.capture());
        assertNotEquals(rawCode, captor.getValue().getCodeHash());
        assertEquals(64, captor.getValue().getCodeHash().length());
    }

    @Test
    void consumeAllowsCodeOnlyOnce() {
        OAuthLoginCodeService service = new OAuthLoginCodeService(repository, 60_000L);
        User user = User.builder()
                .email("user@example.com")
                .password("encoded")
                .username("User")
                .build();
        OAuthLoginCode stored = OAuthLoginCode.builder()
                .user(user)
                .codeHash("hash")
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(repository.findByCodeHash(anyString()))
                .thenReturn(Optional.of(stored))
                .thenReturn(Optional.empty());

        assertSame(user, service.consume("one-time-code"));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.consume("one-time-code")
        );
        verify(repository).delete(stored);
    }

    @Test
    void consumeRejectsExpiredCode() {
        OAuthLoginCodeService service = new OAuthLoginCodeService(repository, 60_000L);
        OAuthLoginCode stored = OAuthLoginCode.builder()
                .user(User.builder()
                        .email("user@example.com")
                        .password("encoded")
                        .username("User")
                        .build())
                .codeHash("hash")
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(repository.findByCodeHash(anyString())).thenReturn(Optional.of(stored));

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.consume("expired-code")
        );
        verify(repository).delete(stored);
    }
}
