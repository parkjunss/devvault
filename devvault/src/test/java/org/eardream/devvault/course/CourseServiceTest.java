package org.eardream.devvault.course;

import org.eardream.devvault.course.entity.Course;
import org.eardream.devvault.course.entity.CourseStatus;
import org.eardream.devvault.course.repository.CourseRepository;
import org.eardream.devvault.course.service.CourseService;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseServiceTest {
    @Test
    void createsAnOwnedCourseInDraftStatus() {
        CourseRepository courseRepository = mock(CourseRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User owner = User.builder().id(1L).email("owner@example.com").password("pw").username("owner").build();
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CourseService service = new CourseService(courseRepository, userRepository);

        Course course = service.create(owner.getEmail(), " 자바 스터디 ", "설명");

        assertEquals("자바 스터디", course.getTitle());
        assertEquals(owner, course.getOwner());
        assertEquals(CourseStatus.DRAFT, course.getStatus());
    }

    @Test
    void rejectsBlankTitle() {
        CourseService service = new CourseService(mock(CourseRepository.class), mock(UserRepository.class));

        assertThrows(ResponseStatusException.class, () -> service.create("owner@example.com", "  ", null));
    }

    @Test
    void hidesAnotherUsersCourse() {
        CourseRepository courseRepository = mock(CourseRepository.class);
        String email = "owner@example.com";
        when(courseRepository.findByIdAndOwnerEmail(5L, email)).thenReturn(Optional.empty());
        CourseService service = new CourseService(courseRepository, mock(UserRepository.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.get(email, 5L));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void activatesAnOwnedCourse() {
        CourseRepository courseRepository = mock(CourseRepository.class);
        String email = "owner@example.com";
        Course course = Course.builder().id(5L).title("자바").status(CourseStatus.DRAFT).build();
        when(courseRepository.findByIdAndOwnerEmail(5L, email)).thenReturn(Optional.of(course));
        CourseService service = new CourseService(courseRepository, mock(UserRepository.class));

        Course activated = service.activate(email, 5L);

        assertEquals(CourseStatus.ACTIVE, activated.getStatus());
    }
}
