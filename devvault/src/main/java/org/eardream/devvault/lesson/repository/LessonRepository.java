package org.eardream.devvault.lesson.repository;

import org.eardream.devvault.lesson.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    List<Lesson> findAllBySectionIdOrderByOrderNoAsc(Long sectionId);

    Optional<Lesson> findByIdAndSectionCourseOwnerEmail(Long id, String ownerEmail);
}
