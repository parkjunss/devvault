package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileStorageService {
    private final StoredFileRepository storedFileRepository;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final Path storageRoot;

    public FileStorageService(StoredFileRepository storedFileRepository,
                              UserRepository userRepository,
                              FolderRepository folderRepository,
                              @Value("${app.storage.location}") String storageLocation) {
        this.storedFileRepository = storedFileRepository;
        this.userRepository = userRepository;
        this.folderRepository = folderRepository;
        this.storageRoot = Path.of(storageLocation).toAbsolutePath().normalize();
    }

    @Transactional
    public StoredFile upload(String ownerEmail, MultipartFile multipartFile) {
        String originalName = validateOriginalName(multipartFile);
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String storedName = UUID.randomUUID().toString();
        Path finalPath = resolveStoredPath(storedName);
        Path tempPath = null;

        try {
            Files.createDirectories(storageRoot);
            tempPath = Files.createTempFile(storageRoot, ".upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(multipartFile.getInputStream(), digest)) {
                Files.copy(input, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(tempPath, finalPath);
            tempPath = null;

            StoredFile storedFile = StoredFile.builder()
                    .owner(owner)
                    .originalName(originalName)
                    .storedName(storedName)
                    .contentType(multipartFile.getContentType())
                    .size(Files.size(finalPath))
                    .checksum(HexFormat.of().formatHex(digest.digest()))
                    .build();
            try {
                return storedFileRepository.saveAndFlush(storedFile);
            } catch (RuntimeException exception) {
                Files.deleteIfExists(finalPath);
                throw exception;
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장할 수 없습니다.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an incomplete upload.
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> list(String ownerEmail, Pageable pageable) {
        return storedFileRepository.findAllByOwnerEmail(ownerEmail, pageable);
    }

    @Transactional(readOnly = true)
    public StoredFile get(String ownerEmail, Long fileId) {
        return storedFileRepository.findByIdAndOwnerEmail(fileId, ownerEmail)
                .orElseThrow(FileStorageService::notFound);
    }

    @Transactional
    public StoredFile update(String ownerEmail, Long fileId, String requestedName,
                             boolean folderChanged, Long folderId) {
        StoredFile storedFile = get(ownerEmail, fileId);
        if (requestedName != null) {
            storedFile.rename(validateFileName(requestedName));
        }
        if (folderChanged) {
            Folder folder = folderId == null ? null : folderRepository.findByIdAndOwnerEmail(folderId, ownerEmail)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다."));
            storedFile.moveTo(folder);
        }
        return storedFile;
    }

    @Transactional(readOnly = true)
    public StoredDownload download(String ownerEmail, Long fileId) {
        StoredFile storedFile = get(ownerEmail, fileId);
        Path path = resolveStoredPath(storedFile.getStoredName());
        if (!Files.isRegularFile(path)) {
            throw notFound();
        }
        return new StoredDownload(storedFile, path);
    }

    private static String validateOriginalName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다.");
        }
        return validateFileName(file.getOriginalFilename());
    }

    private static String validateFileName(String rawName) {
        String cleanName = StringUtils.cleanPath(rawName == null ? "" : rawName.trim());
        if (!StringUtils.hasText(cleanName) || cleanName.length() > 255 || cleanName.contains("..")
                || cleanName.contains("/") || cleanName.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 파일 이름입니다.");
        }
        return cleanName;
    }

    private Path resolveStoredPath(String storedName) {
        Path resolved = storageRoot.resolve(storedName).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw notFound();
        }
        return resolved;
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다.");
    }

    public record StoredDownload(StoredFile metadata, Path path) {
    }
}
