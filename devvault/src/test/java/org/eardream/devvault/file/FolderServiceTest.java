package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FolderServiceTest {
    @Test
    void createsFolderUnderAnOwnedParent() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User owner = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        Folder parent = Folder.builder().id(10L).name("parent").owner(owner).build();
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(folderRepository.findByIdAndOwnerEmail(10L, owner.getEmail())).thenReturn(Optional.of(parent));
        when(folderRepository.save(any(Folder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FolderService service = new FolderService(folderRepository, fileRepository, userRepository);

        Folder created = service.create(owner.getEmail(), " child ", 10L);

        assertEquals("child", created.getName());
        assertEquals(parent, created.getParent());
        assertEquals(owner, created.getOwner());
    }

    @Test
    void returnsOnlyOwnedDirectChildrenAndFiles() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        String email = "owner@example.com";
        Folder parent = Folder.builder().id(10L).name("parent").build();
        Folder child = Folder.builder().id(11L).name("child").build();
        StoredFile file = StoredFile.builder().id(20L).originalName("mine.txt").build();
        var pageable = PageRequest.of(0, 20);
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.of(parent));
        when(folderRepository.findAllByOwnerEmailAndParentIdOrderByNameAsc(email, 10L)).thenReturn(List.of(child));
        when(fileRepository.findAllByOwnerEmailAndFolderId(email, 10L, pageable))
                .thenReturn(new PageImpl<>(List.of(file), pageable, 1));
        FolderService service = new FolderService(folderRepository, fileRepository, userRepository);

        FolderService.FolderChildren result = service.children(email, 10L, pageable);

        assertEquals(List.of(child), result.folders());
        assertEquals(List.of(file), result.files().getContent());
    }

    @Test
    void hidesAnotherUsersParentFolder() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(
                User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build()));
        when(folderRepository.findByIdAndOwnerEmail(10L, "owner@example.com")).thenReturn(Optional.empty());
        FolderService service = new FolderService(folderRepository, fileRepository, userRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create("owner@example.com", "child", 10L));

        assertEquals(404, exception.getStatusCode().value());
    }
}
