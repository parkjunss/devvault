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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class RoleInitializer implements ApplicationRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminUsername;

    public RoleInitializer(RoleRepository roleRepository,
                           UserRepository userRepository,
                           UserRoleRepository userRoleRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.admin-email:}") String adminEmail,
                           @Value("${app.admin-password:}") String adminPassword,
                           @Value("${app.admin-username:관리자}") String adminUsername) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminUsername = adminUsername;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Role userRole = saveIfAbsent("ROLE_USER");
        Role adminRole = saveIfAbsent("ROLE_ADMIN");
        configureAdmin(userRole, adminRole);
    }

    private Role saveIfAbsent(String role) {
        return roleRepository.findByRole(role)
                .orElseGet(() -> roleRepository.save(new Role(role)));
    }

    private void configureAdmin(Role userRole, Role adminRole) {
        if (!StringUtils.hasText(adminEmail)) {
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 50 || !email.contains("@")) {
            throw new IllegalStateException("ADMIN_EMAIL 형식이 올바르지 않습니다.");
        }
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = createAdmin(email);
            grantRole(user, userRole);
        }
        grantRole(user, adminRole);
    }

    private User createAdmin(String email) {
        if (!StringUtils.hasText(adminPassword) || adminPassword.length() < 8 || adminPassword.length() > 72) {
            throw new IllegalStateException("새 관리자 계정에는 8~72자의 ADMIN_PASSWORD가 필요합니다.");
        }
        String username = StringUtils.hasText(adminUsername) ? adminUsername.trim() : "관리자";
        if (username.length() > 50) {
            throw new IllegalStateException("ADMIN_USERNAME은 50자 이하여야 합니다.");
        }
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(adminPassword))
                .username(username)
                .build());
    }

    private void grantRole(User user, Role role) {
        if (user.getUserRoles().stream().anyMatch(userRole -> userRole.getRole().getRole().equals(role.getRole()))) {
            return;
        }
        UserRole userRole = userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        user.getUserRoles().add(userRole);
    }
}
