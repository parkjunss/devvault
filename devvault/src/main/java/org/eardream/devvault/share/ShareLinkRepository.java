package org.eardream.devvault.share;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {
    @EntityGraph(attributePaths = {"file", "file.owner"})
    Optional<ShareLink> findByTokenHash(String tokenHash);
}
