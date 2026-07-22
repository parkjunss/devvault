package org.eardream.devvault.fileTag.repository;

import org.eardream.devvault.fileTag.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByIdAndOwnerEmail(Long id, String email);

    List<Tag> findAllByOwnerEmailOrderByNameAsc(String email);

    boolean existsByOwnerEmailAndName(String email, String name);
}
