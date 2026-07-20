package org.eardream.devvault.auth;

import org.eardream.devvault.user.entity.OauthAccount;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.OAuthAccountRepository;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthServiceTest {

    @Mock OAuthAccountRepository oauthAccountRepository;
    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks GoogleOAuthService googleOAuthService;

    @Test
    void createsUserAndLinksNewGoogleAccount() {
        OAuth2User principal = googlePrincipal();
        User savedUser = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        when(userRoleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        User user = googleOAuthService.login(principal);

        assertEquals(savedUser, user);
        ArgumentCaptor<OauthAccount> account = ArgumentCaptor.forClass(OauthAccount.class);
        verify(oauthAccountRepository).save(account.capture());
        assertEquals(7L, account.getValue().getUserId());
        assertEquals("google-1", account.getValue().getProviderUserId());
    }

    @Test
    void reusesAlreadyLinkedGoogleAccount() {
        User user = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-1"))
                .thenReturn(Optional.of(OauthAccount.builder().userId(7L).provider("google")
                        .providerUserId("google-1").providerEmail("user@example.com").build()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertEquals(user, googleOAuthService.login(googlePrincipal()));
        verify(userRepository, never()).save(any());
        verify(oauthAccountRepository, never()).save(any());
    }

    @Test
    void linksVerifiedGoogleAccountToExistingEmail() {
        User user = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertEquals(user, googleOAuthService.login(googlePrincipal()));
        verify(userRepository, never()).save(any());
        verify(oauthAccountRepository).save(any());
    }

    @Test
    void rejectsUnverifiedGoogleEmail() {
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                () -> googleOAuthService.login(googlePrincipal(false)));
    }

    @Test
    void rejectsDisabledGoogleUser() {
        User user = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        user.setEnabled(false);
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-1"))
                .thenReturn(Optional.of(OauthAccount.builder().userId(7L).provider("google")
                        .providerUserId("google-1").providerEmail("user@example.com").build()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                () -> googleOAuthService.login(googlePrincipal()));
    }

    private OAuth2User googlePrincipal() {
        return googlePrincipal(true);
    }

    private OAuth2User googlePrincipal(boolean verified) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                Map.of("sub", "google-1", "email", "user@example.com", "email_verified", verified, "name", "User"),
                "sub");
    }
}
