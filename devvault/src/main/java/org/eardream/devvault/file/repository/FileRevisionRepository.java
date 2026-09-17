package org.eardream.devvault.file.repository;

import org.eardream.devvault.file.entity.FileRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FileRevisionRepository extends JpaRepository<FileRevision, Long> {
    boolean existsByFileOwnerEmailAndChecksum(String email, String checksum);
    boolean existsByFileOwnerEmailAndChecksumAndFileIdNot(String email, String checksum, Long fileId);
    List<FileRevision> findAllByFileIdOrderByVersionDesc(Long fileId);
    Optional<FileRevision> findByFileIdAndVersion(Long fileId, long version);
}
