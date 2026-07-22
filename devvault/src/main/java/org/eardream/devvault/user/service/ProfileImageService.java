package org.eardream.devvault.user.service;

import org.eardream.devvault.user.dto.ProfileImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


@Service
public class ProfileImageService {

    private static final System.Logger LOGGER =
            System.getLogger(ProfileImageService.class.getName());

    private final UserService userService;
    private final Path profileRoot;

    public ProfileImageService(
            UserService userService,
            @Value("${app.storage.location}") String storageLocation) {

        this.userService = userService;
        this.profileRoot = Path.of(storageLocation)
                .toAbsolutePath()
                .normalize()
                .resolve("profiles");
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "JPEG, PNG, WebP 이미지만 사용할 수 있습니다.");
        };
    }

    private Path resolveProfilePath(String key) {
        Path path = profileRoot.resolve(key).normalize();

        if (!path.startsWith(profileRoot)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "올바르지 않은 이미지 경로입니다.");
        }

        return path;
    }

    private void deletePhysicalFile(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        Path path = resolveProfilePath(key);

        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            // DB 변경은 끝났으므로 고아 파일만 남기고 요청은 실패시키지 않는다.
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "프로필 이미지 삭제 실패: " + path,
                    exception);
        }
    }

    public ProfileImage load(String email) {
        String key = userService.getProfileImageKey(email);
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필 이미지가 없습니다.");
        }
        Path path = resolveProfilePath(key);
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필 이미지가 없습니다.");
        }
        return new ProfileImage(new FileSystemResource(path), mediaType(key));
    }

    public void delete(String email) {
        String oldKey = userService.clearProfileImage(email);
        deletePhysicalFile(oldKey);
    }

    public void replace(String email, MultipartFile file) {
        validate(file);

        String newKey = UUID.randomUUID() + extension(Objects.requireNonNull(file.getContentType()));
        Path newPath = resolveProfilePath(newKey);

        try {
            Files.createDirectories(profileRoot);
            Files.copy(file.getInputStream(), newPath, StandardCopyOption.REPLACE_EXISTING);

            // 이 메서드는 별도 @Transactional 서비스에서 실행
            String oldKey = userService.replaceProfileImage(email, newKey);

            deletePhysicalFile(oldKey);
        } catch (ResponseStatusException exception) {
            deletePhysicalFile(newKey);
            throw exception;
        } catch (Exception exception) {
            deletePhysicalFile(newKey);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "프로필 이미지를 저장하지 못했습니다.",
                    exception
            );
        }
    }

    private static MediaType mediaType(String key) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.endsWith(".jpg")) return MediaType.IMAGE_JPEG;
        if (lowerKey.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lowerKey.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다.");
    }


    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "이미지를 선택해야 합니다.");
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 5MB 이하여야 합니다.");
        }

        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "JPEG, PNG, WebP 이미지만 사용할 수 있습니다.");
        }
    }

}
