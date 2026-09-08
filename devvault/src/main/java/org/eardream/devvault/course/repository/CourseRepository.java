package org.eardream.devvault.course.repository;

import org.eardream.devvault.course.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findAllByOwnerEmailOrderByCreatedAtDesc(String email);

    Optional<Course> findByIdAndOwnerEmail(Long id, String email);
}
