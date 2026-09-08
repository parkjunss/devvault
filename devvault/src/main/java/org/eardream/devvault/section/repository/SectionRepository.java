package org.eardream.devvault.section.repository;

import org.eardream.devvault.section.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SectionRepository extends JpaRepository<Section, Long> {
    List<Section> findAllByCourseIdOrderByOrderNoAsc(Long courseId);

    Optional<Section> findByIdAndCourseOwnerEmail(Long id, String ownerEmail);
}
