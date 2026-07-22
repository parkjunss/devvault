package org.eardream.devvault.file;

import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.repository.StoredFileRepository;
import org.eardream.devvault.fileTag.entity.Tag;
import org.eardream.devvault.fileTag.repository.TagRepository;
import org.eardream.devvault.fileTag.service.TagService;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TagServiceTest {
    @Test
    void createsAnOwnedTagWithTrimmedName() {
        TagRepository tagRepository = mock(TagRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User owner = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(tagRepository.save(any(Tag.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TagService service = new TagService(tagRepository, mock(StoredFileRepository.class), userRepository);

        Tag tag = service.create(owner.getEmail(), " java ");

        assertEquals("java", tag.getName());
        assertEquals(owner, tag.getOwner());
    }

    @Test
    void attachesAnOwnedTagToAnOwnedFile() {
        TagRepository tagRepository = mock(TagRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        Tag tag = Tag.builder().id(3L).name("java").build();
        StoredFile file = StoredFile.builder().id(7L).originalName("code.java").build();
        when(tagRepository.findByIdAndOwnerEmail(3L, email)).thenReturn(Optional.of(tag));
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        TagService service = new TagService(tagRepository, fileRepository, mock(UserRepository.class));

        service.attach(email, 7L, 3L);

        assertTrue(file.getTags().contains(tag));
    }

    @Test
    void hidesAnotherUsersTag() {
        TagRepository tagRepository = mock(TagRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        when(fileRepository.findByIdAndOwnerEmail(7L, email))
                .thenReturn(Optional.of(StoredFile.builder().id(7L).originalName("code.java").build()));
        when(tagRepository.findByIdAndOwnerEmail(3L, email)).thenReturn(Optional.empty());
        TagService service = new TagService(tagRepository, fileRepository, mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.attach(email, 7L, 3L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void detachesAnOwnedTagFromAnOwnedFile() {
        TagRepository tagRepository = mock(TagRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        Tag tag = Tag.builder().id(3L).name("java").build();
        StoredFile file = StoredFile.builder().id(7L).originalName("code.java")
                .tags(new HashSet<>(List.of(tag))).build();
        when(tagRepository.findByIdAndOwnerEmail(3L, email)).thenReturn(Optional.of(tag));
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        TagService service = new TagService(tagRepository, fileRepository, mock(UserRepository.class));

        service.detach(email, 7L, 3L);

        assertFalse(file.getTags().contains(tag));
    }

    @Test
    void rejectsTaggingATrashedFile() {
        TagRepository tagRepository = mock(TagRepository.class);
        StoredFileRepository fileRepository = mock(StoredFileRepository.class);
        String email = "owner@example.com";
        StoredFile file = StoredFile.builder().id(7L).originalName("code.java").build();
        file.softDelete();
        when(fileRepository.findByIdAndOwnerEmail(7L, email)).thenReturn(Optional.of(file));
        TagService service = new TagService(tagRepository, fileRepository, mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.attach(email, 7L, 3L));

        assertEquals(404, exception.getStatusCode().value());
    }
}
