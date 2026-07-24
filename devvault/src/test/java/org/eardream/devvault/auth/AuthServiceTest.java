package org.eardream.devvault.auth;

import org.eardream.devvault.auth.controller.AuthController;
import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.auth.service.AuthService;
import org.eardream.devvault.auth.service.JwtService;
import org.eardream.devvault.auth.service.OAuthLoginCodeService;
import org.eardream.devvault.auth.service.RefreshTokenService;
import org.eardream.devvault.auth.service.EmailDomainValidator;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import jakarta.validation.Validation;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock
    JwtService jwtService;
    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    OAuthLoginCodeService oauthLoginCodeService;
    @Mock
    EmailDomainValidator emailDomainValidator;
    @InjectMocks
    AuthService authService;

    @Test
    void signupCreatesUserWithDefaultRoleAndToken() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issue(any(User.class))).thenReturn("refresh-token");
        when(jwtService.createToken(any(User.class), org.mockito.ArgumentMatchers.eq("refresh-token")))
                .thenReturn(new AuthToken("token", "refresh-token", "Bearer", 900));

        AuthToken token = authService.signup("user@example.com", "password123", "user");

        assertEquals("token", token.accessToken());
        verify(userRoleRepository).save(any());
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(user -> {
            assertNotNull(user.getTermsAcceptedAt());
            assertNotNull(user.getPrivacyAcceptedAt());
            return true;
        }));
    }

    @Test
    void signupRequiresBothLegalConsents() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new AuthController.SignupRequest(
                    "user@example.com", "password123", "user", false, false);
            assertEquals(2, factory.getValidator().validate(request).size());
        }
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
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");
        when(jwtService.createToken(user, "refresh-token"))
                .thenReturn(new AuthToken("token", "refresh-token", "Bearer", 900));

        AuthToken token = authService.login("USER@example.com", "password123");

        assertEquals("token", token.accessToken());
        verify(authenticationManager).authenticate(any());
    }

    @Test
    void refreshRotatesTokenAndReturnsNewPair() {
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();
        when(refreshTokenService.rotate("old-token"))
                .thenReturn(new RefreshTokenService.RotatedToken(user, "new-token"));
        when(jwtService.createToken(user, "new-token"))
                .thenReturn(new AuthToken("access-token", "new-token", "Bearer", 900));

        AuthToken token = authService.refresh("old-token");

        assertEquals("new-token", token.refreshToken());
    }

    @Test
    void logoutRevokesRefreshToken() {
        authService.logout("refresh-token");

        verify(refreshTokenService).revoke("refresh-token");
    }

    @Test
    void exchangesOneTimeOAuthCodeForTokens() {
        User user = User.builder()
                .email("user@example.com")
                .password("encoded")
                .username("user")
                .build();
        when(oauthLoginCodeService.consume("one-time-code")).thenReturn(user);
        when(refreshTokenService.issue(user)).thenReturn("refresh-token");
        when(jwtService.createToken(user, "refresh-token"))
                .thenReturn(new AuthToken("access-token", "refresh-token", "Bearer", 900));

        AuthToken token = authService.exchangeOAuthCode("one-time-code");

        assertEquals("access-token", token.accessToken());
    }

    @Test
    void tokenCreationRejectsDisabledUser() {
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();
        user.setEnabled(false);

        assertThrows(ResponseStatusException.class, () -> authService.createTokens(user));

        verify(refreshTokenService, never()).issue(user);
    }
}
