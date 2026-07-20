package org.eardream.devvault.user;

import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class RoleInitializer implements ApplicationRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final String adminEmail;

    public RoleInitializer(RoleRepository roleRepository,
                           UserRepository userRepository,
                           UserRoleRepository userRoleRepository,
                           @Value("${app.admin-email:}") String adminEmail) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.adminEmail = adminEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        saveIfAbsent("ROLE_USER");
        Role adminRole = saveIfAbsent("ROLE_ADMIN");
        grantConfiguredAdmin(adminRole);
    }

    private Role saveIfAbsent(String role) {
        return roleRepository.findByRole(role)
                .orElseGet(() -> roleRepository.save(new Role(role)));
    }

    private void grantConfiguredAdmin(Role adminRole) {
        if (!StringUtils.hasText(adminEmail)) {
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("ADMIN_EMAIL 사용자를 찾을 수 없습니다: " + email));
        if (user.getUserRoles().stream().anyMatch(userRole -> userRole.getRole().getRole().equals("ROLE_ADMIN"))) {
            return;
        }
        UserRole userRole = userRoleRepository.save(UserRole.builder().user(user).role(adminRole).build());
        user.getUserRoles().add(userRole);
    }
}
