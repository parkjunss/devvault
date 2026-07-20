package org.eardream.devvault.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    Page<StoredFile> findAllByOwnerEmailAndDeletedAtIsNull(String email, Pageable pageable);

    Page<StoredFile> findAllByOwnerEmailAndFolderIdAndDeletedAtIsNull(String email, Long folderId, Pageable pageable);

    Page<StoredFile> findAllByOwnerEmailAndDeletedAtIsNotNull(String email, Pageable pageable);

    @Query(value = """
            select distinct file from StoredFile file
            left join file.tags tag
            where file.owner.email = :ownerEmail
              and file.deletedAt is null
              and (:fileName is null or locate(:fileName, lower(file.originalName)) > 0)
              and (:extension is null or lower(file.originalName) like concat('%.', :extension))
              and (:tagName is null or lower(tag.name) = :tagName)
              and (:favorite is null or file.favorite = :favorite)
            """, countQuery = """
            select count(distinct file.id) from StoredFile file
            left join file.tags tag
            where file.owner.email = :ownerEmail
              and file.deletedAt is null
              and (:fileName is null or locate(:fileName, lower(file.originalName)) > 0)
              and (:extension is null or lower(file.originalName) like concat('%.', :extension))
              and (:tagName is null or lower(tag.name) = :tagName)
              and (:favorite is null or file.favorite = :favorite)
            """)
    Page<StoredFile> search(@Param("ownerEmail") String ownerEmail,
                            @Param("fileName") String fileName,
                            @Param("extension") String extension,
                            @Param("tagName") String tagName,
                            @Param("favorite") Boolean favorite,
                            Pageable pageable);

    Optional<StoredFile> findFirstByOwnerEmailAndChecksum(String ownerEmail, String checksum);

    @Query("""
            select count(file) as fileCount, coalesce(sum(file.size), 0) as usedBytes
            from StoredFile file
            where file.owner.email = :ownerEmail
              and file.deletedAt is null
            """)
    UsageSummary summarizeActiveUsage(@Param("ownerEmail") String ownerEmail);

    Optional<StoredFile> findByIdAndOwnerEmail(Long id, String email);

    @EntityGraph(attributePaths = "tags")
    Optional<StoredFile> findOneByIdAndOwnerEmailAndDeletedAtIsNull(Long id, String email);

    interface UsageSummary {
        long getFileCount();

        long getUsedBytes();
    }
}
