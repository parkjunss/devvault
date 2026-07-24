package org.eardream.devvault.auth;

import org.eardream.devvault.auth.service.GoogleOAuthService;
import org.eardream.devvault.auth.service.OAuthLoginCodeService;
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
    void redirectsOnlyOneTimeCodeToFrontend() throws Exception {
        GoogleOAuthService googleOAuthService = mock(GoogleOAuthService.class);
        OAuthLoginCodeService loginCodeService = mock(OAuthLoginCodeService.class);
        OAuth2LoginHandler handler = new OAuth2LoginHandler(
                googleOAuthService, loginCodeService, "http://localhost:3000/");
        var principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                Map.of("sub", "google-1"), "sub");
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        User user = User.builder().id(7L).email("user@example.com").password("encoded").username("User").build();
        when(googleOAuthService.login(principal)).thenReturn(user);
        when(loginCodeService.issue(user)).thenReturn("one-time-code");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals("http://localhost:3000/login?oauthCode=one-time-code",
                response.getRedirectedUrl());
    }

    @Test
    void redirectsFailureToLogin() throws Exception {
        OAuth2LoginHandler handler = new OAuth2LoginHandler(
                mock(GoogleOAuthService.class), mock(OAuthLoginCodeService.class), "http://localhost:3000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("failed")));

        assertEquals("http://localhost:3000/login?oauthError=1", response.getRedirectedUrl());
    }
}
