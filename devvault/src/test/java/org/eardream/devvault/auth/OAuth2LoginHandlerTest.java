package org.eardream.devvault.auth;

import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.auth.service.AuthService;
import org.eardream.devvault.auth.service.GoogleOAuthService;
import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2LoginHandlerTest {

    @Test
    void redirectsTokensToFrontendFragment() throws Exception {
        GoogleOAuthService googleOAuthService = mock(GoogleOAuthService.class);
        AuthService authService = mock(AuthService.class);
        OAuth2LoginHandler handler = new OAuth2LoginHandler(
                googleOAuthService, authService, "http://localhost:3000/");
        var principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                Map.of("sub", "google-1"), "sub");
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        User user = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        when(googleOAuthService.login(principal)).thenReturn(user);
        when(authService.createTokens(user)).thenReturn(new AuthToken("access-token", "refresh-token", "Bearer", 900));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals("http://localhost:3000/login#accessToken=access-token&refreshToken=refresh-token",
                response.getRedirectedUrl());
    }

    @Test
    void redirectsFailureToLogin() throws Exception {
        OAuth2LoginHandler handler = new OAuth2LoginHandler(
                mock(GoogleOAuthService.class), mock(AuthService.class), "http://localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("failed")));

        assertEquals("http://localhost:3000/login?oauthError=1", response.getRedirectedUrl());
    }
}
