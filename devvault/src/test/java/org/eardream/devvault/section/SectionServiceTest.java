package org.eardream.devvault.section;

import org.eardream.devvault.course.entity.Course;
import org.eardream.devvault.course.repository.CourseRepository;
import org.eardream.devvault.section.entity.Section;
import org.eardream.devvault.section.repository.SectionRepository;
import org.eardream.devvault.section.service.SectionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SectionServiceTest {
    @Test
    void createsASectionUnderAnOwnedCourse() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        String email = "owner@example.com";
        Course course = Course.builder().id(1L).title("자바 스터디").build();
        when(courseRepository.findByIdAndOwnerEmail(1L, email)).thenReturn(Optional.of(course));
        when(sectionRepository.save(any(Section.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SectionService service = new SectionService(sectionRepository, courseRepository);

        Section section = service.create(email, 1L, " 1주차 ", 1);

        assertEquals("1주차", section.getTitle());
        assertEquals(course, section.getCourse());
    }

    @Test
    void rejectsCreatingASectionUnderAnotherUsersCourse() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        String email = "owner@example.com";
        when(courseRepository.findByIdAndOwnerEmail(1L, email)).thenReturn(Optional.empty());
        SectionService service = new SectionService(sectionRepository, courseRepository);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create(email, 1L, "1주차", 1));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void hidesAnotherUsersSection() {
        SectionRepository sectionRepository = mock(SectionRepository.class);
        String email = "owner@example.com";
        when(sectionRepository.findByIdAndCourseOwnerEmail(9L, email)).thenReturn(Optional.empty());
        SectionService service = new SectionService(sectionRepository, mock(CourseRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.rename(email, 9L, "새 제목"));

        assertEquals(404, exception.getStatusCode().value());
    }
}
