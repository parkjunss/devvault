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

@Service
public class PlaybackTokenService {
    private static final String PURPOSE = "file-playback";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final long expirationMs;

    public PlaybackTokenService(JwtEncoder encoder, JwtDecoder decoder,
                                @Value("${app.playback.token-expiration-ms:3600000}") long expirationMs) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.expirationMs = expirationMs;
    }

    public String issue(String email, Long fileId) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("devvault")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusMillis(expirationMs))
                .subject(email)
                .claim("purpose", PURPOSE)
                .claim("fileId", fileId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public PlaybackGrant verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Number fileId = jwt.getClaim("fileId");
            if (!PURPOSE.equals(jwt.getClaimAsString("purpose")) || fileId == null) {
                throw invalidToken();
            }
            return new PlaybackGrant(jwt.getSubject(), fileId.longValue());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "재생 주소가 만료되었거나 유효하지 않습니다.");
    }

    public record PlaybackGrant(String email, Long fileId) {
    }
}
