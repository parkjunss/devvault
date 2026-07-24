package org.eardream.devvault.auth.service;

import org.eardream.devvault.auth.entity.OAuthLoginCode;
import org.eardream.devvault.auth.repository.OAuthLoginCodeRepository;
import org.eardream.devvault.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class OAuthLoginCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthLoginCodeRepository repository;
    private final long expirationMs;

    public OAuthLoginCodeService(
            OAuthLoginCodeRepository repository,
            @Value("${app.oauth.exchange-code-expiration-ms:60000}") long expirationMs
    ) {
        if (expirationMs <= 0) {
            throw new IllegalArgumentException("OAuth 로그인 코드 만료 시간은 양수여야 합니다.");
        }
        this.repository = repository;
        this.expirationMs = expirationMs;
    }

    @Transactional
    public String issue(User user) {
        Instant now = Instant.now();
        repository.deleteByExpiresAtBefore(now);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        repository.save(OAuthLoginCode.builder()
                .user(user)
                .codeHash(hash(rawCode))
                .expiresAt(now.plusMillis(expirationMs))
                .build());
        return rawCode;
    }

    @Transactional
    public User consume(String rawCode) {
        OAuthLoginCode stored = repository.findByCodeHash(hash(rawCode))
                .orElseThrow(OAuthLoginCodeService::invalidCode);

        if (!stored.getExpiresAt().isAfter(Instant.now())) {
            repository.delete(stored);
            throw invalidCode();
        }

        repository.delete(stored);
        return stored.getUser();
    }

    static String hash(String rawCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 OAuth 로그인 코드입니다.");
    }
}
