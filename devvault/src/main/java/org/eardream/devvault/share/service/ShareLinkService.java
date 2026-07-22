package org.eardream.devvault.share.service;

import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.share.entity.ShareLink;
import org.eardream.devvault.share.repository.ShareLinkRepository;
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
public class ShareLinkService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository shareLinkRepository;
    private final FileStorageService fileStorageService;

    public ShareLinkService(ShareLinkRepository shareLinkRepository, FileStorageService fileStorageService) {
        this.shareLinkRepository = shareLinkRepository;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public CreatedShareLink create(String ownerEmail, Long fileId, Instant expiresAt) {
        if (fileId == null || fileId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileId는 양의 정수여야 합니다.");
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료 시간은 현재보다 이후여야 합니다.");
        }
        StoredFile file = fileStorageService.get(ownerEmail, fileId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ShareLink link = shareLinkRepository.saveAndFlush(ShareLink.builder()
                .file(file)
                .tokenHash(hash(token))
                .expiresAt(expiresAt)
                .build());
        return new CreatedShareLink(token, link);
    }

    @Transactional(readOnly = true)
    public FileStorageService.StoredDownload download(String token) {
        ShareLink link = shareLinkRepository.findByTokenHash(hash(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공유 링크를 찾을 수 없습니다."));
        if (!link.getExpiresAt().isAfter(Instant.now())) {
            // ponytail: expired rows stay inert; add scheduled cleanup when table growth matters.
            throw new ResponseStatusException(HttpStatus.GONE, "공유 링크가 만료되었습니다.");
        }
        StoredFile file = link.getFile();
        return fileStorageService.download(file.getOwner().getEmail(), file.getId());
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record CreatedShareLink(String token, ShareLink link) {
    }
}
