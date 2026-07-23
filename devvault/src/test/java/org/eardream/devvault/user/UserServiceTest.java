package org.eardream.devvault.user;

import org.eardream.devvault.user.dto.PasswordChangeRequest;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Test
    void oauthOnlyUserSetsInitialPasswordWithoutCurrentPassword() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserService service = new UserService(users, encoder);
        User user = User.builder().email("user@example.com").password("random")
                .username("User").passwordLoginEnabled(false).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(encoder.encode("new-password")).thenReturn("encoded");

        service.updatePassword(user.getEmail(), new PasswordChangeRequest(null, "new-password"));

        assertTrue(user.isPasswordLoginEnabled());
        verify(encoder).encode("new-password");
    }

    @Test
    void passwordUserStillRequiresCurrentPassword() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserService service = new UserService(users, encoder);
        User user = User.builder().email("user@example.com").password("encoded-old").username("User").build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "encoded-old")).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> service.updatePassword(user.getEmail(), new PasswordChangeRequest("wrong", "new-password")));

        verify(encoder, never()).encode("new-password");
    }
}
