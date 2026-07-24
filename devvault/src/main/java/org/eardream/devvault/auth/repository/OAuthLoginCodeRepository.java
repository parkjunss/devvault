package org.eardream.devvault.auth.repository;

import jakarta.persistence.LockModeType;
import org.eardream.devvault.auth.entity.OAuthLoginCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface OAuthLoginCodeRepository extends JpaRepository<OAuthLoginCode, Long> {

    @EntityGraph(attributePaths = {"user.userRoles", "user.userRoles.role"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthLoginCode> findByCodeHash(String codeHash);

    long deleteByExpiresAtBefore(Instant cutoff);
}
