package org.eardream.devvault.user.repository;

import org.eardream.devvault.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);

    long countByDeletedAtIsNull();

    long countByEnabledTrueAndDeletedAtIsNull();

    @Query("""
            select user from User user
            where (:query is null
               or locate(:query, lower(user.email)) > 0
               or locate(:query, lower(user.username)) > 0)
            """)
    Page<User> search(@Param("query") String query, Pageable pageable);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    @Query("select user from User user where user.email = :email and user.deletedAt is null")
    Optional<User> findByEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.email = :email and user.deletedAt is null")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @Override
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findById(Long id);
}
