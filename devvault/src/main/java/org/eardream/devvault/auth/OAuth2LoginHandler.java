package org.eardream.devvault.auth;

import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.auth.service.AuthService;
import org.eardream.devvault.auth.service.GoogleOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eardream.devvault.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final GoogleOAuthService googleOAuthService;
    private final AuthService authService;

    private final String frontendUrl;

    public OAuth2LoginHandler(GoogleOAuthService googleOAuthService, AuthService authService,
                              @Value("${app.frontend-url}") String frontendUrl) {
        this.googleOAuthService = googleOAuthService;
        this.authService = authService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            User user = googleOAuthService.login(token.getPrincipal());
            AuthToken tokens = authService.createTokens(user);
            redirect(response, "#accessToken=" + encode(tokens.accessToken())
                    + "&refreshToken=" + encode(tokens.refreshToken()));
        } catch (OAuth2AuthenticationException exception) {
            onAuthenticationFailure(request, response, exception);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        redirect(response, "?oauthError=1");
    }

    private void redirect(HttpServletResponse response, String fragment) throws IOException {
        String base = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
        response.sendRedirect(base + "/login" + fragment);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
