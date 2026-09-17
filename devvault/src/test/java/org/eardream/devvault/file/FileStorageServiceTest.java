package org.eardream.devvault.file;

import org.eardream.devvault.file.controller.FileController;
import org.eardream.devvault.folder.entity.Folder;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.folder.repository.FolderRepository;
import org.eardream.devvault.file.repository.StoredFileRepository;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.fileTag.entity.Tag;
import org.eardream.devvault.fileTag.controller.TagController;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void bulkDeleteValidatesAllOwnersBeforeMutatingAndAcceptsDuplicateIds() {
        StoredFileRepository files = mock(StoredFileRepository.class);
        StoredFile first = StoredFile.builder().id(1L).build();
        StoredFile second = StoredFile.builder().id(2L).build();
        when(files.findByIdAndOwnerEmail(1L, "owner@example.com")).thenReturn(Optional.of(first));
        when(files.findByIdAndOwnerEmail(2L, "owner@example.com")).thenReturn(Optional.of(second));
        FileStorageService storage = service(files, mock(UserRepository.class));

        assertThrows(ResponseStatusException.class,
                () -> storage.softDeleteMany("owner@example.com", List.of(1L, 99L)));
        assertFalse(first.isDeleted());
        storage.softDeleteMany("owner@example.com", List.of(1L, 2L, 1L));
        assertTrue(first.isDeleted());
        assertTrue(second.isDeleted());
    }

    @Test
    void uploadsUsingServerGeneratedNameAndChecksum() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.saveAndFlush(any(StoredFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FileStorageService service = service(fileRepository, userRepository);

        StoredFile result = service.upload(user.getEmail(),
                new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()));

        assertNotEquals(result.getOriginalName(), result.getStoredName());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result.getChecksum());
        assertTrue(Files.isRegularFile(tempDir.resolve(result.getStoredName())));
        assertEquals("hello", Files.readString(tempDir.resolve(result.getStoredName())));
    }

    @Test
    void uploadsIntoAnOwnedFolder() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        FolderRepository folderRepository = mock(FolderRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        Folder folder = Folder.builder().id(10L).name("docs").build();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(folderRepository.findByIdAndOwnerEmail(10L, user.getEmail())).thenReturn(Optional.of(folder));
        when(fileRepository.saveAndFlush(any(StoredFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FileStorageService service = new FileStorageService(fileRepository, userRepository, folderRepository, tempDir.toString());

        StoredFile result = service.upload(user.getEmail(),
                new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()), 10L);

        assertEquals(folder, result.getFolder());
    }

    @Test
    void rejectsUploadOverStorageQuota() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner")
                .storageQuotaBytes(4L).build();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service(fileRepository, userRepository).upload(user.getEmail(),
                        new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes())));

        assertEquals(413, exception.getStatusCode().value());
    }

    @Test
    void hidesFilesOwnedByAnotherUser() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(fileRepository.findByIdAndOwnerEmail(7L, "other@example.com")).thenReturn(Optional.empty());
        FileStorageService service = service(fileRepository, userRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.download("other@example.com", 7L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void removesPhysicalFileWhenMetadataCannotBeSaved() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.saveAndFlush(any(StoredFile.class))).thenThrow(new IllegalStateException("database unavailable"));
        FileStorageService service = service(fileRepository, userRepository);

        assertThrows(IllegalStateException.class, () -> service.upload(user.getEmail(),
                new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes())));

        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void listsOnlyFilesOwnedByTheCurrentUser() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        var pageable = PageRequest.of(0, 20);
        var ownedFile = StoredFile.builder().id(1L).originalName("mine.txt").storedName("stored").build();
        when(fileRepository.findAllByOwnerEmailAndDeletedAtIsNull("owner@example.com", pageable))
                .thenReturn(new PageImpl<>(List.of(ownedFile), pageable, 1));
        FileStorageService service = service(fileRepository, userRepository);

        var result = service.list("owner@example.com", pageable);

        assertEquals(List.of(ownedFile), result.getContent());
    }

    @Test
    void combinesNormalizedFileNameExtensionAndTagFilters() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        var pageable = PageRequest.of(0, 20);
        when(fileRepository.search("owner@example.com", "report", "pdf", "java", true, false, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        service.search("owner@example.com", " Report ", ".PDF", " Java ", true, false, pageable);

        verify(fileRepository).search("owner@example.com", "report", "pdf", "java", true, false, pageable);
    }

    @Test
    void filtersRootFilesWhenRequested() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        var pageable = PageRequest.of(0, 20);
        when(fileRepository.search("owner@example.com", null, null, null, null, true, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        service.search("owner@example.com", null, null, null, null, true, pageable);

        verify(fileRepository).search("owner@example.com", null, null, null, null, true, pageable);
    }

    @Test
    void rejectsInvalidExtensionFilter() {
        FileStorageService service = service(mock(StoredFileRepository.class), mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.search("owner@example.com", null, "../pdf", null, null, false, PageRequest.of(0, 20)));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void hidesAnotherUsersFileDetails() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(fileRepository.findByIdAndOwnerEmail(7L, "other@example.com")).thenReturn(Optional.empty());
        FileStorageService service = service(fileRepository, userRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.get("other@example.com", 7L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void returnsFileDetailsWithSortedTags() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("code.java")
                .tags(Set.of(Tag.builder().id(2L).name("spring").build(),
                        Tag.builder().id(1L).name("java").build()))
                .build();
        when(fileRepository.findOneByIdAndOwnerEmailAndDeletedAtIsNull(7L, email)).thenReturn(Optional.of(file));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        FileController.FileDetailResponse response = FileController.FileDetailResponse.from(
                service.getDetail(email, 7L));

        assertEquals(List.of("java", "spring"), response.tags().stream().map(TagController.TagResponse::name).toList());
    }

    @Test
    void renamesAndMovesAnOwnedFile() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("old.txt").build();
        Folder folder = Folder.builder().id(10L).name("docs").build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.of(folder));
        FileStorageService service = new FileStorageService(fileRepository, mock(UserRepository.class),
                folderRepository, tempDir.toString());

        StoredFile result = service.update(email, 7L, " new.txt ", true, 10L);

        assertEquals("new.txt", result.getOriginalName());
        assertEquals(folder, result.getFolder());
    }

    @Test
    void rejectsMovingAFileToAnotherUsersFolder() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("old.txt").build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.empty());
        FileStorageService service = new FileStorageService(fileRepository, mock(UserRepository.class),
                folderRepository, tempDir.toString());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.update(email, 7L, null, true, 10L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void movesAFileBackToRoot() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("old.txt")
                .folder(Folder.builder().id(10L).name("docs").build()).build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        StoredFile result = service.update(email, 7L, null, true, null);

        assertNull(result.getFolder());
    }

    @Test
    void previewsCodeAsPlainText() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("Main.java").storedName("stored")
                .contentType("application/octet-stream").size(4).build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        Files.writeString(tempDir.resolve("stored"), "code");
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        FileStorageService.StoredPreview preview = service.preview(email, 7L);

        assertEquals(MediaType.TEXT_PLAIN, preview.mediaType());
        assertEquals(tempDir.resolve("stored"), preview.path());
    }

    @Test
    void previewsBrowserPlayableMedia() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("clip.mp4").storedName("stored")
                .contentType("video/mp4").size(4).build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        Files.writeString(tempDir.resolve("stored"), "data");

        FileStorageService.StoredPreview preview = service(fileRepository, mock(UserRepository.class)).preview(email, 7L);

        assertEquals(MediaType.parseMediaType("video/mp4"), preview.mediaType());
    }

    @Test
    void rejectsUnsupportedPreviewType() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("archive.zip").storedName("stored")
                .contentType("application/zip").size(4).build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        Files.writeString(tempDir.resolve("stored"), "data");
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.preview(email, 7L));

        assertEquals(415, exception.getStatusCode().value());
    }

    @Test
    void softDeletesAndRestoresAnOwnedFile() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("notes.txt").build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        service.softDelete(email, 7L);
        assertNotNull(file.getDeletedAt());
        service.restore(email, 7L);

        assertNull(file.getDeletedAt());
    }

    @Test
    void hidesTrashedFileFromNormalDetails() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("notes.txt").build();
        file.softDelete();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.get(email, 7L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void listsOnlyTrashedFiles() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        var pageable = PageRequest.of(0, 20);
        StoredFile file = StoredFile.builder().id(7L).originalName("notes.txt").build();
        file.softDelete();
        when(fileRepository.findAllByOwnerEmailAndDeletedAtIsNotNull(email, pageable))
                .thenReturn(new PageImpl<>(List.of(file), pageable, 1));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        assertEquals(List.of(file), service.trash(email, pageable).getContent());
    }

    @Test
    void permanentlyDeletesTrashedMetadataAndPhysicalFile() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("notes.txt").storedName("stored").build();
        file.softDelete();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        Files.writeString(tempDir.resolve("stored"), "data");
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        service.deletePermanently(email, 7L);

        assertFalse(Files.exists(tempDir.resolve("stored")));
        verify(fileRepository).delete(file);
        verify(fileRepository).flush();
    }

    @Test
    void favoritesAndUnfavoritesAnOwnedFile() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("notes.txt").build();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        FileStorageService service = service(fileRepository, mock(UserRepository.class));

        service.setFavorite(email, 7L, true);
        assertTrue(file.isFavorite());

        service.setFavorite(email, 7L, false);
        assertFalse(file.isFavorite());
    }

    @Test
    void rejectsDuplicateChecksumBeforeMovingFileIntoPlace() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        String checksum = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.findFirstByOwnerEmailAndChecksum(user.getEmail(), checksum))
                .thenReturn(Optional.of(StoredFile.builder().id(9L).checksum(checksum).build()));
        FileStorageService service = service(fileRepository, userRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.upload(user.getEmail(),
                        new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes())));

        assertEquals(409, exception.getStatusCode().value());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void convertsConcurrentDuplicateInsertToConflictAndRemovesPhysicalFile() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.saveAndFlush(any(StoredFile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate checksum"));
        FileStorageService service = service(fileRepository, userRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.upload(user.getEmail(),
                        new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes())));

        assertEquals(409, exception.getStatusCode().value());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void returnsActiveFileCountAndUsedBytesForDashboard() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        StoredFileRepository.UsageSummary usage = mock(StoredFileRepository.UsageSummary.class);
        when(usage.getFileCount()).thenReturn(3L);
        when(usage.getUsedBytes()).thenReturn(8192L);
        when(fileRepository.summarizeActiveUsage("owner@example.com")).thenReturn(usage);
        when(fileRepository.sumStoredBytes("owner@example.com")).thenReturn(8192L);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(
                User.builder().email("owner@example.com").password("pw").username("owner").build()));
        FileStorageService service = service(fileRepository, userRepository);

        FileStorageService.DashboardSummary result = service.dashboard("owner@example.com");

        assertEquals(3L, result.fileCount());
        assertEquals(8192L, result.usedBytes());
        assertEquals(50_000_000_000L, result.quotaBytes());
    }

    private FileStorageService service(StoredFileRepository fileRepository, UserRepository userRepository) {
        return new FileStorageService(fileRepository, userRepository, mock(FolderRepository.class), tempDir.toString());
    }
}
