package org.eardream.devvault.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    Page<StoredFile> findAllByOwnerEmail(String email, Pageable pageable);

    Page<StoredFile> findAllByOwnerEmailAndFolderId(String email, Long folderId, Pageable pageable);

    @Query(value = """
            select distinct file from StoredFile file
            left join file.tags tag
            where file.owner.email = :ownerEmail
              and (:fileName is null or locate(:fileName, lower(file.originalName)) > 0)
              and (:extension is null or lower(file.originalName) like concat('%.', :extension))
              and (:tagName is null or lower(tag.name) = :tagName)
            """, countQuery = """
            select count(distinct file.id) from StoredFile file
            left join file.tags tag
            where file.owner.email = :ownerEmail
              and (:fileName is null or locate(:fileName, lower(file.originalName)) > 0)
              and (:extension is null or lower(file.originalName) like concat('%.', :extension))
              and (:tagName is null or lower(tag.name) = :tagName)
            """)
    Page<StoredFile> search(@Param("ownerEmail") String ownerEmail,
                            @Param("fileName") String fileName,
                            @Param("extension") String extension,
                            @Param("tagName") String tagName,
                            Pageable pageable);

    Optional<StoredFile> findByIdAndOwnerEmail(Long id, String email);

    @EntityGraph(attributePaths = "tags")
    Optional<StoredFile> findOneByIdAndOwnerEmail(Long id, String email);
}
