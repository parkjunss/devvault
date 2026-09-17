package org.eardream.devvault.file.repository;

import org.eardream.devvault.file.entity.StoredFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findAllByOwnerId(Long ownerId);

    Page<StoredFile> findAllByOwnerEmailAndDeletedAtIsNull(String email, Pageable pageable);

    Page<StoredFile> findAllByOwnerEmailAndFolderIdAndDeletedAtIsNull(String email, Long folderId, Pageable pageable);

    boolean existsByOwnerEmailAndFolderIdAndDeletedAtIsNull(String email, Long folderId);

    List<StoredFile> findAllByOwnerEmailAndFolderIdAndDeletedAtIsNotNull(String email, Long folderId);

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
              and (:rootOnly = false or file.folder is null)
            """, countQuery = """
            select count(distinct file.id) from StoredFile file
            left join file.tags tag
            where file.owner.email = :ownerEmail
              and file.deletedAt is null
              and (:fileName is null or locate(:fileName, lower(file.originalName)) > 0)
              and (:extension is null or lower(file.originalName) like concat('%.', :extension))
              and (:tagName is null or lower(tag.name) = :tagName)
              and (:favorite is null or file.favorite = :favorite)
              and (:rootOnly = false or file.folder is null)
            """)
    Page<StoredFile> search(@Param("ownerEmail") String ownerEmail,
                            @Param("fileName") String fileName,
                            @Param("extension") String extension,
                            @Param("tagName") String tagName,
                            @Param("favorite") Boolean favorite,
                            @Param("rootOnly") boolean rootOnly,
                            Pageable pageable);

    Optional<StoredFile> findFirstByOwnerEmailAndChecksum(String ownerEmail, String checksum);

    @Query("""
            select count(file) as fileCount, coalesce(sum(file.size), 0) as usedBytes
            from StoredFile file
            where file.owner.email = :ownerEmail
              and file.deletedAt is null
            """)
    UsageSummary summarizeActiveUsage(@Param("ownerEmail") String ownerEmail);

    @Query(value = """
            select (select coalesce(sum(f.size), 0) from stored_files f join users u on u.id = f.owner_id where u.email = :ownerEmail)
                 + (select coalesce(sum(r.size), 0) from file_revisions r join stored_files f on f.id = r.file_id
                    join users u on u.id = f.owner_id where u.email = :ownerEmail)
            """, nativeQuery = true)
    long sumStoredBytes(@Param("ownerEmail") String ownerEmail);

    @Query(value = """
            select count(*) as fileCount, coalesce(sum(size), 0)
                + (select coalesce(sum(size), 0) from file_revisions) as usedBytes from stored_files
            """, nativeQuery = true)
    UsageSummary summarizeAllUsage();

    @Query("""
            select file from StoredFile file
            where (:query is null
               or locate(:query, lower(file.originalName)) > 0
               or locate(:query, lower(file.owner.email)) > 0)
            """)
    Page<StoredFile> searchAll(@Param("query") String query, Pageable pageable);

    Optional<StoredFile> findByIdAndOwnerEmail(Long id, String email);

    @EntityGraph(attributePaths = "tags")
    Optional<StoredFile> findOneByIdAndOwnerEmailAndDeletedAtIsNull(Long id, String email);

    interface UsageSummary {
        long getFileCount();

        long getUsedBytes();
    }
}
