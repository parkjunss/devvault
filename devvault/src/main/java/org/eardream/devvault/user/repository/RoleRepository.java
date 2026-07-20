package org.eardream.devvault.user.repository;

import org.eardream.devvault.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRole(String role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select role from Role role where role.role = :role")
    Optional<Role> findByRoleForUpdate(@Param("role") String role);
}
