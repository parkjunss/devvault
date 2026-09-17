package org.eardream.devvault.file.controller;

import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.file.service.FileAccessTokenService;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.fileTag.entity.Tag;
import org.eardream.devvault.fileTag.controller.TagController;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileStorageService fileStorageService;
    private final FileAccessTokenService fileAccessTokenService;
    private final boolean accelRedirectEnabled;
    private final String accelRedirectPrefix;

    public FileController(FileStorageService fileStorageService,
                          FileAccessTokenService fileAccessTokenService,
                          @Value("${app.storage.accel-redirect-enabled:false}") boolean accelRedirectEnabled,
                          @Value("${app.storage.accel-redirect-prefix:/__devvault_files/}")
                          String accelRedirectPrefix) {
        this.fileStorageService = fileStorageService;
        this.fileAccessTokenService = fileAccessTokenService;
        this.accelRedirectEnabled = accelRedirectEnabled;
        this.accelRedirectPrefix = accelRedirectPrefix.endsWith("/")
                ? accelRedirectPrefix : accelRedirectPrefix + "/";
    }

    @PostMapping
    ResponseEntity<FileResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(required = false) Long folderId) {
        StoredFile storedFile = fileStorageService.upload(jwt.getSubject(), file, folderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(FileResponse.from(storedFile));
    }

    @GetMapping
    Page<FileResponse> list(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String extension,
                            @RequestParam(required = false) String tag,
                            @RequestParam(required = false) Boolean favorite,
                            @RequestParam(defaultValue = "false") boolean rootOnly,
                            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                            Pageable pageable) {
        return fileStorageService.search(jwt.getSubject(), name, extension, tag, favorite, rootOnly, pageable)
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
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(new InputStreamResource(Files.newInputStream(download.path())));
    }

    @PostMapping("/download-tickets")
    DownloadTicketResponse downloadTicket(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody FileIdsRequest request) {
        String email = jwt.getSubject();
        fileStorageService.downloads(email, request.fileIds());
        String token = fileAccessTokenService.issueDownload(email, request.fileIds());
        return new DownloadTicketResponse("/api/files/download/" + token);
    }

    @GetMapping("/download/{token}")
    ResponseEntity<StreamingResponseBody> ticketDownload(@PathVariable String token) {
        FileAccessTokenService.DownloadGrant grant = fileAccessTokenService.verifyDownload(token);
        List<FileStorageService.StoredDownload> downloads =
                fileStorageService.downloads(grant.email(), grant.fileIds());
        if (downloads.size() == 1) {
            FileStorageService.StoredDownload download = downloads.get(0);
            StoredFile metadata = download.metadata();
            StreamingResponseBody body = output -> {
                try (InputStream input = Files.newInputStream(download.path())) {
                    input.transferTo(output);
                }
            };
            return downloadHeaders(ContentDisposition.attachment()
                            .filename(metadata.getOriginalName(), StandardCharsets.UTF_8).build())
                    .contentType(parseMediaType(metadata.getContentType()))
                    .contentLength(metadata.getSize())
                    .body(body);
        }

        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                Set<String> usedNames = new HashSet<>();
                for (FileStorageService.StoredDownload download : downloads) {
                    zip.putNextEntry(new ZipEntry(uniqueZipName(download.metadata().getOriginalName(), usedNames)));
                    try (InputStream input = Files.newInputStream(download.path())) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        };
        return downloadHeaders(ContentDisposition.attachment().filename("devvault-files.zip").build())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
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

    @PostMapping("/{id}/playback-url")
    PlaybackUrlResponse playbackUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.preview(jwt.getSubject(), id);
        String token = fileAccessTokenService.issuePlayback(jwt.getSubject(), id);
        return new PlaybackUrlResponse("/api/files/playback/" + token);
    }

    @GetMapping("/playback/{token}")
    ResponseEntity<?> playback(@PathVariable String token) {
        FileAccessTokenService.PlaybackGrant grant = fileAccessTokenService.verifyPlayback(token);
        FileStorageService.StoredPreview preview = fileStorageService.preview(grant.email(), grant.fileId());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(preview.metadata().getOriginalName(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(preview.mediaType())
                .contentLength(preview.metadata().getSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff");
        if (accelRedirectEnabled) {
            return response.header("X-Accel-Redirect",
                    accelRedirectPrefix + preview.metadata().getStoredName()).build();
        }
        Resource resource = new FileSystemResource(preview.path());
        return response.body(resource);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> softDelete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.softDelete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-delete")
    ResponseEntity<Void> softDeleteMany(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody FileIdsRequest request) {
        fileStorageService.softDeleteMany(jwt.getSubject(), request.fileIds());
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

    @PostMapping("/{id}/favorite")
    ResponseEntity<Void> favorite(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.setFavorite(jwt.getSubject(), id, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/favorite")
    ResponseEntity<Void> unfavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        fileStorageService.setFavorite(jwt.getSubject(), id, false);
        return ResponseEntity.noContent().build();
    }

    private static MediaType parseMediaType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static ResponseEntity.BodyBuilder downloadHeaders(ContentDisposition disposition) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'");
    }

    private static String uniqueZipName(String originalName, Set<String> usedNames) {
        String normalized = originalName == null ? "file" : originalName.replace('\\', '/');
        String safeName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (safeName.isBlank()) safeName = "file";
        if (usedNames.add(safeName)) return safeName;

        int dot = safeName.lastIndexOf('.');
        String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        for (int index = 2; ; index++) {
            String candidate = stem + " (" + index + ")" + extension;
            if (usedNames.add(candidate)) return candidate;
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
                               String checksum, boolean favorite, Instant createdAt) {
        public static FileResponse from(StoredFile file) {
            return new FileResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getContentType(),
                    file.getSize(), file.getChecksum(), file.isFavorite(), file.getCreatedAt());
        }
    }

    public record FileDetailResponse(Long id, String originalName, Long folderId, String contentType, long size,
                                     String checksum, boolean favorite, Instant createdAt,
                                     List<TagController.TagResponse> tags) {
        public static FileDetailResponse from(StoredFile file) {
            List<TagController.TagResponse> tags = file.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(TagController.TagResponse::from)
                    .toList();
            return new FileDetailResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getContentType(),
                    file.getSize(), file.getChecksum(), file.isFavorite(), file.getCreatedAt(), tags);
        }
    }

    public record TrashFileResponse(Long id, String originalName, Long folderId, long size, Instant deletedAt) {
        static TrashFileResponse from(StoredFile file) {
            return new TrashFileResponse(file.getId(), file.getOriginalName(),
                    file.getFolder() == null ? null : file.getFolder().getId(), file.getSize(), file.getDeletedAt());
        }
    }

    public record PlaybackUrlResponse(String url) {
    }

    public record FileIdsRequest(
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> fileIds) {
    }

    public record DownloadTicketResponse(String url) {
    }
}
