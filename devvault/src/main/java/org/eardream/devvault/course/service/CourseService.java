package org.eardream.devvault.course.service;

import org.eardream.devvault.course.entity.Course;
import org.eardream.devvault.course.repository.CourseRepository;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CourseService {
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;

    public CourseService(CourseRepository courseRepository, UserRepository userRepository) {
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Course create(String ownerEmail, String title, String description) {
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return courseRepository.save(Course.builder()
                .owner(owner)
                .title(validateTitle(title))
                .description(description)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Course> list(String ownerEmail) {
        return courseRepository.findAllByOwnerEmailOrderByCreatedAtDesc(ownerEmail);
    }

    @Transactional(readOnly = true)
    public Course get(String ownerEmail, Long courseId) {
        return owned(ownerEmail, courseId);
    }

    @Transactional
    public Course rename(String ownerEmail, Long courseId, String title, String description) {
        Course course = owned(ownerEmail, courseId);
        course.rename(validateTitle(title), description);
        return course;
    }

    @Transactional
    public Course activate(String ownerEmail, Long courseId) {
        Course course = owned(ownerEmail, courseId);
        course.activate();
        return course;
    }

    @Transactional
    public Course archive(String ownerEmail, Long courseId) {
        Course course = owned(ownerEmail, courseId);
        course.archive();
        return course;
    }

    Course owned(String ownerEmail, Long courseId) {
        return courseRepository.findByIdAndOwnerEmail(courseId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."));
    }

    private static String validateTitle(String requestedTitle) {
        String title = requestedTitle == null ? "" : requestedTitle.trim();
        if (!StringUtils.hasText(title) || title.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 강의 제목입니다.");
        }
        return title;
    }
}
