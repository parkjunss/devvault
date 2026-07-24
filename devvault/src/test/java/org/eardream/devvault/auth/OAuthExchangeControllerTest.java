package org.eardream.devvault.auth;

import org.eardream.devvault.auth.controller.AuthController;
import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthExchangeControllerTest {

    @Test
    void exchangesOneTimeCodeThroughAuthEndpoint() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.exchangeOAuthCode("one-time-code"))
                .thenReturn(new AuthToken(
                        "access-token",
                        "refresh-token",
                        "Bearer",
                        900
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .build();

        mvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"one-time-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));

        verify(authService).exchangeOAuthCode("one-time-code");
    }

    @Test
    void rejectsBlankExchangeCode() throws Exception {
        AuthService authService = mock(AuthService.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .build();

        mvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":" "}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }
}
