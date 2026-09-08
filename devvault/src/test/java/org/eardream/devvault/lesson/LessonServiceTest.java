package org.eardream.devvault.lesson;

import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.lesson.entity.Lesson;
import org.eardream.devvault.lesson.entity.LessonSourceType;
import org.eardream.devvault.lesson.repository.LessonRepository;
import org.eardream.devvault.lesson.service.LessonService;
import org.eardream.devvault.lesson.service.TextExtractionService;
import org.eardream.devvault.section.entity.Section;
import org.eardream.devvault.section.repository.SectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LessonServiceTest {
    private static final String EMAIL = "owner@example.com";

    @Test
    void createsAWrittenLessonUnderAnOwnedSection() {
        LessonRepository lessonRepository = mock(LessonRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        Section section = Section.builder().id(2L).title("1주차").build();
        when(sectionRepository.findByIdAndCourseOwnerEmail(2L, EMAIL)).thenReturn(Optional.of(section));
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LessonService service = new LessonService(lessonRepository, sectionRepository,
                mock(FileStorageService.class), mock(TextExtractionService.class));

        Lesson lesson = service.createWritten(EMAIL, 2L, "역전파", "# 역전파\n내용", 1);

        assertEquals(LessonSourceType.WRITTEN, lesson.getSourceType());
        assertEquals(section, lesson.getSection());
    }

    @Test
    void rejectsCreatingALessonUnderAnotherUsersSection() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        when(sectionRepository.findByIdAndCourseOwnerEmail(2L, EMAIL)).thenReturn(Optional.empty());
        LessonService service = new LessonService(mock(LessonRepository.class), sectionRepository,
                mock(FileStorageService.class), mock(TextExtractionService.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createWritten(EMAIL, 2L, "역전파", "내용", 1));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void createsALessonFromAnUploadedFileAndKeepsTheOriginal() {
        LessonRepository lessonRepository = mock(LessonRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        TextExtractionService textExtractionService = mock(TextExtractionService.class);
        Section section = Section.builder().id(2L).title("1주차").build();
        StoredFile storedFile = StoredFile.builder().id(9L).originalName("week1.pdf").contentType("application/pdf").build();
        when(sectionRepository.findByIdAndCourseOwnerEmail(2L, EMAIL)).thenReturn(Optional.of(section));
        when(fileStorageService.download(EMAIL, 9L))
                .thenReturn(new FileStorageService.StoredDownload(storedFile, Path.of("week1.pdf")));
        when(textExtractionService.extract(any(), any(), any())).thenReturn("추출된 본문");
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LessonService service = new LessonService(lessonRepository, sectionRepository, fileStorageService, textExtractionService);

        Lesson lesson = service.createFromFile(EMAIL, 2L, 9L, "1주차 교재", 1);

        assertEquals(LessonSourceType.UPLOAD, lesson.getSourceType());
        assertEquals(storedFile, lesson.getSourceFile());
        assertEquals("추출된 본문", lesson.getContentMd());
        assertNotNull(lesson.getExtractedAt());
    }

    @Test
    void rejectsWhenExtractionYieldsNoText() {
        LessonRepository lessonRepository = mock(LessonRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        TextExtractionService textExtractionService = mock(TextExtractionService.class);
        Section section = Section.builder().id(2L).build();
        StoredFile storedFile = StoredFile.builder().id(9L).originalName("empty.pdf").build();
        when(sectionRepository.findByIdAndCourseOwnerEmail(2L, EMAIL)).thenReturn(Optional.of(section));
        when(fileStorageService.download(EMAIL, 9L))
                .thenReturn(new FileStorageService.StoredDownload(storedFile, Path.of("empty.pdf")));
        when(textExtractionService.extract(any(), any(), any())).thenReturn("   ");
        LessonService service = new LessonService(lessonRepository, sectionRepository, fileStorageService, textExtractionService);

        assertThrows(ResponseStatusException.class, () -> service.createFromFile(EMAIL, 2L, 9L, "제목", 1));
    }

    @Test
    void hidesAnotherUsersLesson() {
        LessonRepository lessonRepository = mock(LessonRepository.class);
        when(lessonRepository.findByIdAndSectionCourseOwnerEmail(3L, EMAIL)).thenReturn(Optional.empty());
        LessonService service = new LessonService(lessonRepository, mock(SectionRepository.class),
                mock(FileStorageService.class), mock(TextExtractionService.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.get(EMAIL, 3L));

        assertEquals(404, exception.getStatusCode().value());
    }
}
