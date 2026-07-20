package org.eardream.devvault.share;

import org.eardream.devvault.file.FileStorageService;
import org.eardream.devvault.file.StoredFile;
import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareLinkServiceTest {

    @Test
    void createsHashedShareTokenForOwnedFile() {
        ShareLinkRepository repository = mock(ShareLinkRepository.class);
        FileStorageService files = mock(FileStorageService.class);
        StoredFile file = file();
        when(files.get("owner@example.com", 7L)).thenReturn(file);
        when(repository.saveAndFlush(any(ShareLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ShareLinkService service = new ShareLinkService(repository, files);
        Instant expiresAt = Instant.now().plusSeconds(3600);

        ShareLinkService.CreatedShareLink result = service.create("owner@example.com", 7L, expiresAt);

        ArgumentCaptor<ShareLink> saved = ArgumentCaptor.forClass(ShareLink.class);
        verify(repository).saveAndFlush(saved.capture());
        assertNotEquals(result.token(), saved.getValue().getTokenHash());
        assertEquals(64, saved.getValue().getTokenHash().length());
        assertEquals(expiresAt, saved.getValue().getExpiresAt());
    }

    @Test
    void rejectsExpirationInThePast() {
        ShareLinkRepository repository = mock(ShareLinkRepository.class);
        FileStorageService files = mock(FileStorageService.class);
        ShareLinkService service = new ShareLinkService(repository, files);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create("owner@example.com", 7L, Instant.now().minusSeconds(1)));

        assertEquals(400, exception.getStatusCode().value());
        verify(files, never()).get(any(), any());
    }

    @Test
    void rejectsMissingFileId() {
        ShareLinkRepository repository = mock(ShareLinkRepository.class);
        FileStorageService files = mock(FileStorageService.class);
        ShareLinkService service = new ShareLinkService(repository, files);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create("owner@example.com", null, Instant.now().plusSeconds(3600)));

        assertEquals(400, exception.getStatusCode().value());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void returnsGoneForExpiredShareLink() {
        ShareLinkRepository repository = mock(ShareLinkRepository.class);
        FileStorageService files = mock(FileStorageService.class);
        String token = "share-token";
        ShareLink link = ShareLink.builder().file(file()).tokenHash(ShareLinkService.hash(token))
                .expiresAt(Instant.now().minusSeconds(1)).build();
        when(repository.findByTokenHash(ShareLinkService.hash(token))).thenReturn(Optional.of(link));
        ShareLinkService service = new ShareLinkService(repository, files);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.download(token));

        assertEquals(410, exception.getStatusCode().value());
        verify(files, never()).download(any(), any());
    }

    @Test
    void resolvesActiveShareLinkThroughExistingDownloadChecks() {
        ShareLinkRepository repository = mock(ShareLinkRepository.class);
        FileStorageService files = mock(FileStorageService.class);
        String token = "share-token";
        StoredFile file = file();
        ShareLink link = ShareLink.builder().file(file).tokenHash(ShareLinkService.hash(token))
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        FileStorageService.StoredDownload download = new FileStorageService.StoredDownload(file, Path.of("stored"));
        when(repository.findByTokenHash(ShareLinkService.hash(token))).thenReturn(Optional.of(link));
        when(files.download("owner@example.com", 7L)).thenReturn(download);
        ShareLinkService service = new ShareLinkService(repository, files);

        assertEquals(download, service.download(token));
    }

    private static StoredFile file() {
        User owner = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        return StoredFile.builder().id(7L).owner(owner).originalName("notes.txt").storedName("stored")
                .contentType("text/plain").size(4).checksum("checksum").build();
    }
}
