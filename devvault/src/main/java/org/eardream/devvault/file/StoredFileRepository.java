package org.eardream.devvault.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    Page<StoredFile> findAllByOwnerEmail(String email, Pageable pageable);

    Page<StoredFile> findAllByOwnerEmailAndFolderId(String email, Long folderId, Pageable pageable);

    Optional<StoredFile> findByIdAndOwnerEmail(Long id, String email);
}
