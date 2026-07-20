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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void listsOnlyOwnedRootFolders() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        Folder root = Folder.builder().id(10L).name("root").build();
        when(folderRepository.findAllByOwnerEmailAndParentIsNullOrderByNameAsc(email))
                .thenReturn(List.of(root));
        FolderService service = new FolderService(
                folderRepository, mock(StoredFileRepository.class), mock(UserRepository.class));

        assertEquals(List.of(root), service.roots(email));
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

    @Test
    void renamesAndMovesAnOwnedFolder() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        Folder folder = Folder.builder().id(10L).name("old").build();
        Folder parent = Folder.builder().id(20L).name("parent").build();
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.of(folder));
        when(folderRepository.findByIdAndOwnerEmail(20L, email)).thenReturn(Optional.of(parent));
        FolderService service = new FolderService(
                folderRepository, mock(StoredFileRepository.class), mock(UserRepository.class));

        Folder updated = service.update(email, 10L, " new ", true, 20L);

        assertEquals("new", updated.getName());
        assertEquals(parent, updated.getParent());
    }

    @Test
    void rejectsMovingFolderUnderItsOwnDescendant() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        Folder folder = Folder.builder().id(10L).name("root").build();
        Folder child = Folder.builder().id(20L).name("child").parent(folder).build();
        Folder descendant = Folder.builder().id(30L).name("descendant").parent(child).build();
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.of(folder));
        when(folderRepository.findByIdAndOwnerEmail(30L, email)).thenReturn(Optional.of(descendant));
        FolderService service = new FolderService(
                folderRepository, mock(StoredFileRepository.class), mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.update(email, 10L, null, true, 30L));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void movesFolderBackToRoot() {
        FolderRepository folderRepository = mock(FolderRepository.class);
        String email = "owner@example.com";
        Folder folder = Folder.builder().id(10L).name("child")
                .parent(Folder.builder().id(20L).name("parent").build()).build();
        when(folderRepository.findByIdAndOwnerEmail(10L, email)).thenReturn(Optional.of(folder));
        FolderService service = new FolderService(
                folderRepository, mock(StoredFileRepository.class), mock(UserRepository.class));

        Folder updated = service.update(email, 10L, null, true, null);

        assertNull(updated.getParent());
    }
}
