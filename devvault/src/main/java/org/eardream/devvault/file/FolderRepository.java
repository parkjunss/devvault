package org.eardream.devvault.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {
    Optional<Folder> findByIdAndOwnerEmail(Long id, String email);

    List<Folder> findAllByOwnerEmailAndParentIdOrderByNameAsc(String email, Long parentId);

    List<Folder> findAllByOwnerEmailAndParentIsNullOrderByNameAsc(String email);

    boolean existsByOwnerEmailAndParentIdAndName(String email, Long parentId, String name);

    boolean existsByOwnerEmailAndParentIdAndNameAndIdNot(String email, Long parentId, String name, Long id);
}
