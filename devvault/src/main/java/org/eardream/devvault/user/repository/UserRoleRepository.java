package org.eardream.devvault.user.repository;

import org.eardream.devvault.user.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    void deleteAllByUserId(Long userId);

    long countByRoleRole(String role);

    @Query("""
            select count(userRole) from UserRole userRole
            where userRole.role.role = :role
              and userRole.user.enabled = true
            """)
    long countEnabledByRole(@Param("role") String role);
}
