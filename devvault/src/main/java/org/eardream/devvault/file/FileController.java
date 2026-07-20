package org.eardream.devvault.file;

import tools.jackson.databind.JsonNode;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

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
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String extension,
                            @RequestParam(required = false) String tag,
                            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                            Pageable pageable) {
        return fileStorageService.search(jwt.getSubject(), name, extension, tag, pageable)
                .map(FileResponse::from);
    }

    @GetMapping("/{id}")
    FileDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return FileDetailResponse.from(fileStorageService.getDetail(jwt.getSubject(), id));
    }

    @PatchMapping("/{id}")
    FileResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                        @RequestBody UpdateFileRequest request) {
        boolean folderChanged = request.folderId() != null;
        Long folderId = parseFolderId(request.folderId());
        if (request.name() == null && !folderChanged) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 값을 입력해야 합니다.");
        }
        return FileResponse.from(fileStorageService.update(
                jwt.getSubject(), id, request.name(), folderChanged, folderId));
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

    @GetMapping("/{id}/preview")
    ResponseEntity<InputStreamResource> preview(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable Long id) throws IOException {
        FileStorageService.StoredPreview preview = fileStorageService.preview(jwt.getSubject(), id);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(preview.metadata().getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(preview.mediaType())
                .contentLength(preview.metadata().getSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(new InputStreamResource(Files.newInputStream(preview.path())));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> softDelete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.softDelete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    Page<TrashFileResponse> trash(@AuthenticationPrincipal Jwt jwt,
                                  @PageableDefault(size = 20, sort = "deletedAt", direction = Sort.Direction.DESC)
                                  Pageable pageable) {
        return fileStorageService.trash(jwt.getSubject(), pageable).map(TrashFileResponse::from);
    }

    @PostMapping("/{id}/restore")
    ResponseEntity<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.restore(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    ResponseEntity<Void> deletePermanently(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.deletePermanently(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    private static MediaType parseMediaType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static Long parseFolderId(JsonNode folderId) {
        if (folderId == null || folderId.isNull()) {
            return null;
        }
        if (!folderId.isIntegralNumber() || !folderId.canConvertToLong() || folderId.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "folderId는 양의 정수 또는 null이어야 합니다.");
        }
        return folderId.longValue();
    }

    public record UpdateFileRequest(String name, JsonNode folderId) {
    }

    public record FileResponse(Long id, String originalName, Long folderId, String contentType, long size,
                               String checksum, Instant createdAt) {
        static FileResponse from(StoredFile file) {
            return new FileResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getContentType(),
                    file.getSize(), file.getChecksum(), file.getCreatedAt());
        }
    }

    public record FileDetailResponse(Long id, String originalName, Long folderId, String contentType, long size,
                                     String checksum, Instant createdAt,
                                     List<TagController.TagResponse> tags) {
        static FileDetailResponse from(StoredFile file) {
            List<TagController.TagResponse> tags = file.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(TagController.TagResponse::from)
                    .toList();
            return new FileDetailResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getContentType(),
                    file.getSize(), file.getChecksum(), file.getCreatedAt(), tags);
        }
    }

    public record TrashFileResponse(Long id, String originalName, Long folderId, long size, Instant deletedAt) {
        static TrashFileResponse from(StoredFile file) {
            return new TrashFileResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getSize(), file.getDeletedAt());
        }
    }
}
