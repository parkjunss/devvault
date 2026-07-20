package org.eardream.devvault.auth;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.eardream.devvault.user.entity.User;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuth2LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final GoogleOAuthService googleOAuthService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            User user = googleOAuthService.login(token.getPrincipal());
            writeJson(response, HttpServletResponse.SC_OK, authService.createTokens(user));
        } catch (OAuth2AuthenticationException exception) {
            onAuthenticationFailure(request, response, exception);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                Map.of("message", "Google 로그인에 실패했습니다."));
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
