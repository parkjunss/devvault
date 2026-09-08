package org.eardream.devvault.section.service;

import org.eardream.devvault.course.entity.Course;
import org.eardream.devvault.course.repository.CourseRepository;
import org.eardream.devvault.section.entity.Section;
import org.eardream.devvault.section.repository.SectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class SectionService {
    private final SectionRepository sectionRepository;
    private final CourseRepository courseRepository;

    public SectionService(SectionRepository sectionRepository, CourseRepository courseRepository) {
        this.sectionRepository = sectionRepository;
        this.courseRepository = courseRepository;
    }

    @Transactional
    public Section create(String ownerEmail, Long courseId, String title, int orderNo) {
        Course course = ownedCourse(ownerEmail, courseId);
        return sectionRepository.save(Section.builder()
                .course(course)
                .title(validateTitle(title))
                .orderNo(orderNo)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Section> list(String ownerEmail, Long courseId) {
        ownedCourse(ownerEmail, courseId);
        return sectionRepository.findAllByCourseIdOrderByOrderNoAsc(courseId);
    }

    @Transactional
    public Section rename(String ownerEmail, Long sectionId, String title) {
        Section section = owned(ownerEmail, sectionId);
        section.rename(validateTitle(title));
        return section;
    }

    Section owned(String ownerEmail, Long sectionId) {
        return sectionRepository.findByIdAndCourseOwnerEmail(sectionId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "주차를 찾을 수 없습니다."));
    }

    private Course ownedCourse(String ownerEmail, Long courseId) {
        return courseRepository.findByIdAndOwnerEmail(courseId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."));
    }

    private static String validateTitle(String requestedTitle) {
        String title = requestedTitle == null ? "" : requestedTitle.trim();
        if (!StringUtils.hasText(title) || title.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 주차 제목입니다.");
        }
        return title;
    }
}
