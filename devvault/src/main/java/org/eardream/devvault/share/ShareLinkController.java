package org.eardream.devvault.share;

import org.eardream.devvault.file.FileStorageService;
import org.eardream.devvault.file.StoredFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

@RestController
public class ShareLinkController {
    private final ShareLinkService shareLinkService;

    public ShareLinkController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @PostMapping("/api/share-links")
    ResponseEntity<ShareLinkResponse> create(@AuthenticationPrincipal Jwt jwt,
                                             @RequestBody CreateShareLinkRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청 본문이 필요합니다.");
        }
        ShareLinkService.CreatedShareLink created = shareLinkService.create(
                jwt.getSubject(), request.fileId(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ShareLinkResponse(
                created.token(), "/share/" + created.token(), created.link().getExpiresAt()));
    }

    @GetMapping("/share/{token}")
    ResponseEntity<InputStreamResource> download(@PathVariable String token) throws IOException {
        FileStorageService.StoredDownload download = shareLinkService.download(token);
        StoredFile file = download.metadata();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType(file.getContentType()))
                .contentLength(file.getSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(Files.newInputStream(download.path())));
    }

    private static MediaType mediaType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public record CreateShareLinkRequest(Long fileId, Instant expiresAt) {
    }

    public record ShareLinkResponse(String token, String path, Instant expiresAt) {
    }
}
