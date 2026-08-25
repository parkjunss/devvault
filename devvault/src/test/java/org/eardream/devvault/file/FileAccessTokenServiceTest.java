package org.eardream.devvault.file;

import org.eardream.devvault.file.service.FileAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAccessTokenServiceTest {

    @Test
    void issuesFileScopedSignedPlaybackToken() {
        SecretKey key = new SecretKeySpec(
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        FileAccessTokenService service = new FileAccessTokenService(
                NimbusJwtEncoder.withSecretKey(key).build(),
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(),
                3_600_000);

        String token = service.issuePlayback("user@example.com", 42L);
        FileAccessTokenService.PlaybackGrant grant = service.verifyPlayback(token);

        assertEquals("user@example.com", grant.email());
        assertEquals(42L, grant.fileId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.verifyPlayback(token + "tampered"));
    }

    @Test
    void issuesSignedDownloadTokenForSelectedFiles() {
        SecretKey key = new SecretKeySpec(
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        FileAccessTokenService service = new FileAccessTokenService(
                NimbusJwtEncoder.withSecretKey(key).build(),
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(),
                3_600_000);

        String token = service.issueDownload("user@example.com", List.of(7L, 9L));
        FileAccessTokenService.DownloadGrant grant = service.verifyDownload(token);

        assertEquals("user@example.com", grant.email());
        assertEquals(List.of(7L, 9L), grant.fileIds());
    }
}
