package org.eardream.devvault.file.service;

import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.repository.StoredFileRepository;
import org.eardream.devvault.folder.entity.Folder;
import org.eardream.devvault.folder.repository.FolderRepository;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {
    private static final System.Logger LOGGER = System.getLogger(FileStorageService.class.getName());
    private static final Set<String> PREVIEW_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "txt", "md", "java", "kt", "kts", "js", "jsx", "ts", "tsx", "css", "scss",
            "html", "htm", "xml", "json", "yaml", "yml", "properties", "sql", "py", "go",
            "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bat", "ps1", "gradle");
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
        return upload(ownerEmail, multipartFile, null);
    }

    @Transactional
    public StoredFile upload(String ownerEmail, MultipartFile multipartFile, Long folderId) {
        String originalName = validateOriginalName(multipartFile);
        User owner = userRepository.findByEmailForUpdate(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        long usedBytes = storedFileRepository.sumStoredBytes(ownerEmail);
        if (multipartFile.getSize() > owner.getStorageQuotaBytes() - usedBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "저장소 용량이 부족합니다. 요금제를 업그레이드해 주세요.");
        }
        Folder folder = folderId == null ? null : findOwnedFolder(ownerEmail, folderId);
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
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (storedFileRepository.findFirstByOwnerEmailAndChecksum(ownerEmail, checksum).isPresent()) {
                throw duplicateFile();
            }
            moveIntoPlace(tempPath, finalPath);
            tempPath = null;

            StoredFile storedFile = StoredFile.builder()
                    .owner(owner)
                    .folder(folder)
                    .originalName(originalName)
                    .storedName(storedName)
                    .contentType(multipartFile.getContentType())
                    .size(Files.size(finalPath))
                    .checksum(checksum)
                    .build();
            try {
                return storedFileRepository.saveAndFlush(storedFile);
            } catch (DataIntegrityViolationException exception) {
                deletePhysicalFile(finalPath);
                throw duplicateFile(exception);
            } catch (RuntimeException exception) {
                deletePhysicalFile(finalPath);
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
        return storedFileRepository.findAllByOwnerEmailAndDeletedAtIsNull(ownerEmail, pageable);
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> search(String ownerEmail, String requestedName, String requestedExtension,
                                   String requestedTag, Boolean favorite, Pageable pageable) {
        String name = normalizeFilter(requestedName, 255, "파일명");
        String extension = normalizeExtension(requestedExtension);
        String tag = normalizeFilter(requestedTag, 50, "태그");
        if (name == null && extension == null && tag == null && favorite == null) {
            return list(ownerEmail, pageable);
        }
        return storedFileRepository.search(ownerEmail, name, extension, tag, favorite, pageable);
    }

    @Transactional
    public StoredFile setFavorite(String ownerEmail, Long fileId, boolean favorite) {
        StoredFile file = get(ownerEmail, fileId);
        file.setFavorite(favorite);
        return file;
    }

    @Transactional(readOnly = true)
    public DashboardSummary dashboard(String ownerEmail) {
        StoredFileRepository.UsageSummary usage = storedFileRepository.summarizeActiveUsage(ownerEmail);
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new DashboardSummary(usage.getFileCount(), storedFileRepository.sumStoredBytes(ownerEmail),
                owner.getStorageQuotaBytes());
    }

    @Transactional(readOnly = true)
    public StoredFile get(String ownerEmail, Long fileId) {
        return storedFileRepository.findByIdAndOwnerEmail(fileId, ownerEmail)
                .filter(file -> !file.isDeleted())
                .orElseThrow(FileStorageService::notFound);
    }

    @Transactional(readOnly = true)
    public StoredFile getDetail(String ownerEmail, Long fileId) {
        return storedFileRepository.findOneByIdAndOwnerEmailAndDeletedAtIsNull(fileId, ownerEmail)
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
            Folder folder = folderId == null ? null : findOwnedFolder(ownerEmail, folderId);
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

    @Transactional(readOnly = true)
    public StoredPreview preview(String ownerEmail, Long fileId) {
        StoredDownload download = download(ownerEmail, fileId);
        return new StoredPreview(download.metadata(), download.path(), previewMediaType(download.metadata()));
    }

    @Transactional
    public void softDelete(String ownerEmail, Long fileId) {
        get(ownerEmail, fileId).softDelete();
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> trash(String ownerEmail, Pageable pageable) {
        return storedFileRepository.findAllByOwnerEmailAndDeletedAtIsNotNull(ownerEmail, pageable);
    }

    @Transactional
    public void restore(String ownerEmail, Long fileId) {
        getTrashed(ownerEmail, fileId).restore();
    }

    @Transactional
    public void deletePermanently(String ownerEmail, Long fileId) {
        StoredFile file = getTrashed(ownerEmail, fileId);
        Path path = resolveStoredPath(file.getStoredName());
        storedFileRepository.delete(file);
        storedFileRepository.flush();
        // ponytail: after-commit deletion can leave an orphan on I/O failure; add a cleanup job if observed.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletePhysicalFile(path);
                }
            });
        } else {
            deletePhysicalFile(path);
        }
    }

    private StoredFile getTrashed(String ownerEmail, Long fileId) {
        return storedFileRepository.findByIdAndOwnerEmail(fileId, ownerEmail)
                .filter(StoredFile::isDeleted)
                .orElseThrow(FileStorageService::notFound);
    }

    private Folder findOwnedFolder(String ownerEmail, Long folderId) {
        if (folderId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "folderId는 양의 정수여야 합니다.");
        }
        return folderRepository.findByIdAndOwnerEmail(folderId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다."));
    }

    private static MediaType previewMediaType(StoredFile file) {
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (PREVIEW_IMAGE_TYPES.contains(contentType)) {
            return MediaType.parseMediaType(contentType);
        }
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            return MediaType.APPLICATION_PDF;
        }
        if (contentType.startsWith("audio/") || contentType.startsWith("video/")) {
            return MediaType.parseMediaType(contentType);
        }
        if (contentType.startsWith("text/") || CODE_EXTENSIONS.contains(extensionOf(file.getOriginalName()))) {
            return MediaType.TEXT_PLAIN;
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "미리보기를 지원하지 않는 파일입니다.");
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void deletePhysicalFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Failed to delete physical file " + path, exception);
        }
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

    private static String normalizeFilter(String value, int maxLength, String label) {
        if (value == null || !StringUtils.hasText(value.trim())) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 검색어가 너무 깁니다.");
        }
        return normalized;
    }

    private static String normalizeExtension(String value) {
        String extension = normalizeFilter(value, 20, "확장자");
        if (extension == null) {
            return null;
        }
        if (extension.startsWith(".")) {
            extension = extension.substring(1);
        }
        if (!extension.matches("[\\p{L}\\p{N}]+(?:[._-][\\p{L}\\p{N}]+)*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 확장자입니다.");
        }
        return extension;
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

    private static ResponseStatusException duplicateFile() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "같은 내용의 파일이 이미 존재합니다.");
    }

    private static ResponseStatusException duplicateFile(Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "같은 내용의 파일이 이미 존재합니다.", cause);
    }

    public record StoredDownload(StoredFile metadata, Path path) {
    }

    public record StoredPreview(StoredFile metadata, Path path, MediaType mediaType) {
    }

    public record DashboardSummary(long fileCount, long usedBytes, long quotaBytes) {
    }
}
