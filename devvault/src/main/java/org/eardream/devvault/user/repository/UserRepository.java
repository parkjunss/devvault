package org.eardream.devvault.user.repository;

import org.eardream.devvault.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findByEmail(String email);

    @Override
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findById(Long id);
}
