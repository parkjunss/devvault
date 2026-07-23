package org.eardream.devvault.file;

import org.eardream.devvault.file.service.PlaybackTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackTokenServiceTest {

    @Test
    void issuesFileScopedSignedPlaybackToken() {
        SecretKey key = new SecretKeySpec(
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        PlaybackTokenService service = new PlaybackTokenService(
                NimbusJwtEncoder.withSecretKey(key).build(),
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(),
                3_600_000);

        String token = service.issue("user@example.com", 42L);
        PlaybackTokenService.PlaybackGrant grant = service.verify(token);

        assertEquals("user@example.com", grant.email());
        assertEquals(42L, grant.fileId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.verify(token + "tampered"));
    }
}
