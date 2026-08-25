package org.eardream.devvault.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@Service
public class FileAccessTokenService {
    private static final String PLAYBACK_PURPOSE = "file-playback";
    private static final String DOWNLOAD_PURPOSE = "file-download";
    private static final long DOWNLOAD_EXPIRATION_MS = 300_000;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final long playbackExpirationMs;

    public FileAccessTokenService(JwtEncoder encoder, JwtDecoder decoder,
                                  @Value("${app.playback.token-expiration-ms:3600000}")
                                  long playbackExpirationMs) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.playbackExpirationMs = playbackExpirationMs;
    }

    public String issuePlayback(String email, Long fileId) {
        Instant issuedAt = Instant.now();
        return encode(JwtClaimsSet.builder()
                .issuer("devvault")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusMillis(playbackExpirationMs))
                .subject(email)
                .claim("purpose", PLAYBACK_PURPOSE)
                .claim("fileId", fileId)
                .build());
    }

    public PlaybackGrant verifyPlayback(String token) {
        Jwt jwt = decode(token, PLAYBACK_PURPOSE);
        Number fileId = jwt.getClaim("fileId");
        if (fileId == null) {
            throw invalidToken();
        }
        return new PlaybackGrant(jwt.getSubject(), fileId.longValue());
    }

    public String issueDownload(String email, List<Long> fileIds) {
        validateFileIds(fileIds);
        Instant issuedAt = Instant.now();
        return encode(JwtClaimsSet.builder()
                .issuer("devvault")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusMillis(DOWNLOAD_EXPIRATION_MS))
                .subject(email)
                .claim("purpose", DOWNLOAD_PURPOSE)
                .claim("fileIds", fileIds)
                .build());
    }

    public DownloadGrant verifyDownload(String token) {
        Jwt jwt = decode(token, DOWNLOAD_PURPOSE);
        List<?> claim = jwt.getClaim("fileIds");
        if (claim == null) {
            throw invalidToken();
        }
        List<Long> fileIds = claim.stream()
                .map(value -> value instanceof Number number ? number.longValue() : null)
                .toList();
        validateFileIds(fileIds);
        return new DownloadGrant(jwt.getSubject(), fileIds);
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private Jwt decode(String token, String purpose) {
        try {
            Jwt jwt = decoder.decode(token);
            if (!purpose.equals(jwt.getClaimAsString("purpose"))) {
                throw invalidToken();
            }
            return jwt;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidToken();
        }
    }

    private static void validateFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > 100
                || fileIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw invalidToken();
        }
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 파일 접근 URL입니다.");
    }

    public record PlaybackGrant(String email, Long fileId) {
    }

    public record DownloadGrant(String email, List<Long> fileIds) {
    }
}
