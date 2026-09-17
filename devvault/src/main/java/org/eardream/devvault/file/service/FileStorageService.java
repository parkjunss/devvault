package org.eardream.devvault.file.service;

import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.entity.FileRevision;
import org.eardream.devvault.file.repository.FileRevisionRepository;
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
import java.util.List;
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
    private final FileRevisionRepository revisions;

    public FileStorageService(StoredFileRepository storedFileRepository,
                              UserRepository userRepository,
                              FolderRepository folderRepository,
                              FileRevisionRepository revisions,
                              @Value("${app.storage.location}") String storageLocation) {
        this.storedFileRepository = storedFileRepository;
        this.revisions = revisions;
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
            if (storedFileRepository.findFirstByOwnerEmailAndChecksum(ownerEmail, checksum).isPresent()
                    || revisions.existsByFileOwnerEmailAndChecksum(ownerEmail, checksum)) {
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
    public List<RevisionInfo> revisions(String email, Long fileId) {
        StoredFile file = get(email, fileId);
        var result = new java.util.ArrayList<RevisionInfo>();
        result.add(RevisionInfo.from(FileRevision.archive(file), true));
        revisions.findAllByFileIdOrderByVersionDesc(fileId).forEach(r -> result.add(RevisionInfo.from(r, false)));
        return result;
    }

    @Transactional
    public StoredFile saveVersion(String email, Long fileId, long expectedVersion, MultipartFile upload) {
        validateOriginalName(upload);
        User owner = lockOwner(email);
        StoredFile file = get(email, fileId);
        checkVersion(file, expectedVersion);
        if (file.getSize() > 50L * 1024 * 1024 || upload.getSize() > 50L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "편집은 50MiB 이하만 지원합니다.");
        String type = upload.getContentType();
        boolean pdf = "application/pdf".equals(file.getContentType());
        if (!(pdf && "application/pdf".equals(type)) && !(PREVIEW_IMAGE_TYPES.contains(file.getContentType() == null ? "" : file.getContentType()) && "image/png".equals(type))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF 또는 PNG 편집 결과만 저장할 수 있습니다.");
        }
        try (InputStream input = upload.getInputStream()) {
            return replaceVersion(owner, file, input, upload.getSize(), type, pdf ? "pdf" : "png", true);
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "편집본을 저장할 수 없습니다.", e); }
    }

    @Transactional
    public StoredFile restoreVersion(String email, Long fileId, long version, long expectedVersion) {
        User owner = lockOwner(email);
        StoredFile file = get(email, fileId);
        checkVersion(file, expectedVersion);
        FileRevision revision = revisions.findByFileIdAndVersion(fileId, version).orElseThrow(FileStorageService::notFound);
        try (InputStream input = Files.newInputStream(resolveStoredPath(revision.getStoredName()))) {
            return replaceVersion(owner, file, input, revision.getSize(), revision.getContentType(), extensionOf(revision.getOriginalName()), false);
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "이전 버전을 복원할 수 없습니다.", e); }
    }

    private User lockOwner(String email) {
        return userRepository.findByEmailForUpdate(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static void checkVersion(StoredFile file, long expected) {
        if (expected < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 버전이 필요합니다.");
        if (file.getVersion() != expected) throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 탭에서 파일을 변경했습니다. 최신 버전을 확인해 주세요.");
    }

    private StoredFile replaceVersion(User owner, StoredFile file, InputStream input, long expectedSize,
                                      String type, String extension, boolean validateSignature) {
        long available = owner.getStorageQuotaBytes() - storedFileRepository.sumStoredBytes(owner.getEmail());
        if (expectedSize > available) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "이전 버전을 포함한 저장소 용량이 부족합니다.");
        String storedName = UUID.randomUUID().toString();
        Path target = resolveStoredPath(storedName);
        Path temp = null;
        boolean moved = false;
        try {
            Files.createDirectories(storageRoot);
            temp = Files.createTempFile(storageRoot, ".revision-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                long size = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    size += read;
                    if (size > available || size > 50L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "편집본 용량이 제한을 초과합니다.");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (validateSignature) {
                byte[] header;
                try (var stream = Files.newInputStream(temp)) { header = stream.readNBytes(8); }
                byte[] signature = "application/pdf".equals(type) ? "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                        : new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10};
                if (header.length < signature.length || !java.util.Arrays.equals(signature, java.util.Arrays.copyOf(header, signature.length)))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "편집본의 파일 형식이 올바르지 않습니다.");
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (checksum.equals(file.getChecksum())) return file;
            if (storedFileRepository.findFirstByOwnerEmailAndChecksum(owner.getEmail(), checksum).filter(other -> !other.getId().equals(file.getId())).isPresent()
                    || revisions.existsByFileOwnerEmailAndChecksumAndFileIdNot(owner.getEmail(), checksum, file.getId())) throw duplicateFile();
            revisions.save(FileRevision.archive(file));
            moveIntoPlace(temp, target);
            temp = null;
            moved = true;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) deletePhysicalFile(target); }
            });
            file.replaceContent(storedName, type, Files.size(target), checksum, extension);
            storedFileRepository.flush();
            return file;
        } catch (IOException e) {
            if (moved) deletePhysicalFile(target);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 버전을 저장할 수 없습니다.", e);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        catch (RuntimeException e) { if (moved) deletePhysicalFile(target); throw e; }
        finally { if (temp != null) deletePhysicalFile(temp); }
    }

    public record RevisionInfo(long version, String originalName, String contentType, long size, String checksum,
                               java.time.Instant createdAt, boolean current) {
        static RevisionInfo from(FileRevision r, boolean current) {
            return new RevisionInfo(r.getVersion(), r.getOriginalName(), r.getContentType(), r.getSize(), r.getChecksum(), r.getCreatedAt(), current);
        }
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> list(String ownerEmail, Pageable pageable) {
        return storedFileRepository.findAllByOwnerEmailAndDeletedAtIsNull(ownerEmail, pageable);
    }

    @Transactional
    public void softDeleteMany(String ownerEmail, List<Long> fileIds) {
        // Resolve every owned file before changing anything: an invalid ID cannot cause a partial deletion.
        List<StoredFile> files = fileIds.stream().distinct().map(id -> get(ownerEmail, id)).toList();
        files.forEach(StoredFile::softDelete);
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> search(String ownerEmail, String requestedName, String requestedExtension,
                                   String requestedTag, Boolean favorite, boolean rootOnly, Pageable pageable) {
        String name = normalizeFilter(requestedName, 255, "파일명");
        String extension = normalizeExtension(requestedExtension);
        String tag = normalizeFilter(requestedTag, 50, "태그");
        if (name == null && extension == null && tag == null && favorite == null && !rootOnly) {
            return list(ownerEmail, pageable);
        }
        return storedFileRepository.search(ownerEmail, name, extension, tag, favorite, rootOnly, pageable);
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
    public List<StoredDownload> downloads(String ownerEmail, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > 100
                || fileIds.stream().anyMatch(id -> id == null || id <= 0)
                || fileIds.stream().distinct().count() != fileIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 ID는 중복 없이 1개 이상 100개 이하여야 합니다.");
        }
        // ponytail: at most 100 existing ownership checks; add one bulk query only if this path becomes hot.
        return fileIds.stream().map(id -> download(ownerEmail, id)).toList();
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
        List<Path> paths = new java.util.ArrayList<>(revisions.findAllByFileIdOrderByVersionDesc(fileId).stream().map(r -> resolveStoredPath(r.getStoredName())).toList());
        paths.add(resolveStoredPath(file.getStoredName()));
        storedFileRepository.delete(file);
        storedFileRepository.flush();
        // ponytail: after-commit deletion can leave an orphan on I/O failure; add a cleanup job if observed.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    paths.forEach(FileStorageService::deletePhysicalFile);
                }
            });
        } else {
            paths.forEach(FileStorageService::deletePhysicalFile);
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
