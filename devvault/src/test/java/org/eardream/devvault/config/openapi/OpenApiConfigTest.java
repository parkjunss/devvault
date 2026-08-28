package org.eardream.devvault.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesProjectInfoAndJwtAuthentication() {
        OpenAPI openAPI = new OpenApiConfig().devVaultOpenApi();

        assertEquals("DevVault API", openAPI.getInfo().getTitle());
        assertEquals("v1", openAPI.getInfo().getVersion());
        assertNotNull(openAPI.getComponents().getSecuritySchemes().get("bearerAuth"));
    }

    @Test
    void servesSwaggerUiAndApiDocsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("DevVault API"));
        mockMvc.perform(get("/api/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void allowsSameOriginSignedFilePreviewFrames() throws Exception {
        mockMvc.perform(get("/api/files/playback/invalid-token"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }
}
