package org.eardream.devvault.file;

import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping
    ResponseEntity<FileResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam("file") MultipartFile file) {
        StoredFile storedFile = fileStorageService.upload(jwt.getSubject(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(FileResponse.from(storedFile));
    }

    @GetMapping
    Page<FileResponse> list(@AuthenticationPrincipal Jwt jwt,
                            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                            Pageable pageable) {
        return fileStorageService.list(jwt.getSubject(), pageable).map(FileResponse::from);
    }

    @GetMapping("/{id}")
    FileResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return FileResponse.from(fileStorageService.get(jwt.getSubject(), id));
    }

    @GetMapping("/{id}/download")
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable Long id) throws IOException {
        FileStorageService.StoredDownload download = fileStorageService.download(jwt.getSubject(), id);
        StoredFile metadata = download.metadata();
        MediaType mediaType = parseMediaType(metadata.getContentType());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(metadata.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(metadata.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(Files.newInputStream(download.path())));
    }

    private static MediaType parseMediaType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public record FileResponse(Long id, String originalName, String contentType, long size,
                               String checksum, Instant createdAt) {
        static FileResponse from(StoredFile file) {
            return new FileResponse(file.getId(), file.getOriginalName(), file.getContentType(),
                    file.getSize(), file.getChecksum(), file.getCreatedAt());
        }
    }
}
