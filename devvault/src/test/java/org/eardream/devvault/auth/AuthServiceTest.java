package org.eardream.devvault.auth;

import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @InjectMocks AuthService authService;

    @Test
    void signupCreatesUserWithDefaultRoleAndToken() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.createToken(any(User.class))).thenReturn(new AuthToken("token", "Bearer", 900));

        AuthToken token = authService.signup("user@example.com", "password123", "user");

        assertEquals("token", token.accessToken());
        verify(userRoleRepository).save(any());
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThrows(ResponseStatusException.class,
                () -> authService.signup("user@example.com", "password123", "user"));
    }

    @Test
    void loginAuthenticatesAndReturnsToken() {
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        when(jwtService.createToken(user)).thenReturn(new AuthToken("token", "Bearer", 900));

        AuthToken token = authService.login("USER@example.com", "password123");

        assertEquals("token", token.accessToken());
        verify(authenticationManager).authenticate(any());
    }
}
