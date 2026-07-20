package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void uploadsUsingServerGeneratedNameAndChecksum() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.saveAndFlush(any(StoredFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FileStorageService service = new FileStorageService(fileRepository, userRepository, tempDir.toString());

        StoredFile result = service.upload(user.getEmail(),
                new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()));

        assertNotEquals(result.getOriginalName(), result.getStoredName());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result.getChecksum());
        assertTrue(Files.isRegularFile(tempDir.resolve(result.getStoredName())));
        assertEquals("hello", Files.readString(tempDir.resolve(result.getStoredName())));
    }

    @Test
    void hidesFilesOwnedByAnotherUser() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(fileRepository.findByIdAndOwnerEmail(7L, "other@example.com")).thenReturn(Optional.empty());
        FileStorageService service = new FileStorageService(fileRepository, userRepository, tempDir.toString());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.download("other@example.com", 7L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void removesPhysicalFileWhenMetadataCannotBeSaved() throws Exception {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User user = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(fileRepository.saveAndFlush(any(StoredFile.class))).thenThrow(new IllegalStateException("database unavailable"));
        FileStorageService service = new FileStorageService(fileRepository, userRepository, tempDir.toString());

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
        when(fileRepository.findAllByOwnerEmail("owner@example.com", pageable))
                .thenReturn(new PageImpl<>(List.of(ownedFile), pageable, 1));
        FileStorageService service = new FileStorageService(fileRepository, userRepository, tempDir.toString());

        var result = service.list("owner@example.com", pageable);

        assertEquals(List.of(ownedFile), result.getContent());
    }

    @Test
    void hidesAnotherUsersFileDetails() {
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(fileRepository.findByIdAndOwnerEmail(7L, "other@example.com")).thenReturn(Optional.empty());
        FileStorageService service = new FileStorageService(fileRepository, userRepository, tempDir.toString());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.get("other@example.com", 7L));

        assertEquals(404, exception.getStatusCode().value());
    }
}
